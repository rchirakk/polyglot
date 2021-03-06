package me.dinowernli.grpc.polyglot.grpc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

import com.google.auth.Credentials;
import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.StatusException;
import io.grpc.auth.ClientAuthInterceptor;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import polyglot.ConfigProto;

import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

/** Knows how to construct grpc channels. */
public class ChannelFactory {
  private final ConfigProto.CallConfiguration callConfiguration;
  private final ListeningExecutorService authExecutor;

  public static ChannelFactory create(ConfigProto.CallConfiguration callConfiguration) {
    ListeningExecutorService authExecutor = listeningDecorator(
        Executors.newCachedThreadPool(new ThreadFactoryBuilder().setDaemon(true).build()));
    return new ChannelFactory(callConfiguration, authExecutor);
  }

  public ChannelFactory(
      ConfigProto.CallConfiguration callConfiguration, ListeningExecutorService authExecutor) {
    this.callConfiguration = callConfiguration;
    this.authExecutor = authExecutor;
  }

  public Channel createChannel(HostAndPort endpoint) {
    NettyChannelBuilder nettyChannelBuilder = createChannelBuilder(endpoint);

    if (!callConfiguration.getTlsClientOverrideAuthority().isEmpty()) {
      nettyChannelBuilder.overrideAuthority(callConfiguration.getTlsClientOverrideAuthority());
    }

    return nettyChannelBuilder.build();
  }

  public Channel createChannelWithCredentials(HostAndPort endpoint, Credentials credentials) {
    return ClientInterceptors.intercept(
        createChannel(endpoint), new ClientAuthInterceptor(credentials, authExecutor));
  }

  private NettyChannelBuilder createChannelBuilder(HostAndPort endpoint) {
    if (!callConfiguration.getUseTls()) {
      return NettyChannelBuilder.forAddress(endpoint.getHostText(), endpoint.getPort())
          .negotiationType(NegotiationType.PLAINTEXT);
    } else {
      return NettyChannelBuilder.forAddress(endpoint.getHostText(), endpoint.getPort())
          .sslContext(createSslContext())
          .negotiationType(NegotiationType.TLS)
          .intercept(metadataInterceptor());
    }
  }

  private ClientInterceptor metadataInterceptor() {
    ClientInterceptor interceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          final io.grpc.MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, final Channel next) {
        return new ClientInterceptors.CheckedForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
          @Override
          protected void checkedStart(Listener<RespT> responseListener, Metadata headers)
              throws StatusException {
            for (ConfigProto.CallMetadataEntry entry : callConfiguration.getMetadataList()) {
              Metadata.Key<String> key = Metadata.Key.of(entry.getName(), Metadata.ASCII_STRING_MARSHALLER);
              headers.put(key, entry.getValue());
            }
            delegate().start(responseListener, headers);
          }
        };
      }
    };

    return interceptor;
  }

  private SslContext createSslContext() {
    SslContextBuilder resultBuilder = GrpcSslContexts.forClient();
    if (!callConfiguration.getTlsCaCertPath().isEmpty()) {
      resultBuilder.trustManager(loadFile(callConfiguration.getTlsCaCertPath()));
    }
    if (!callConfiguration.getTlsClientCertPath().isEmpty()) {
      resultBuilder.keyManager(
          loadFile(callConfiguration.getTlsClientCertPath()),
          loadFile(callConfiguration.getTlsClientKeyPath()));
    }
    try {
      return resultBuilder.build();
    } catch (SSLException e) {
      throw new RuntimeException("Unable to build sslcontext for client call", e);
    }
  }

  private static File loadFile(String fileName) {
    Path filePath = Paths.get(fileName);
    Preconditions.checkArgument(Files.exists(filePath), "File " + fileName + " was not found");
    return filePath.toFile();
  }
}
