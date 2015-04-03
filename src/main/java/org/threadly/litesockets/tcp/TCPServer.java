package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.litesockets.Server;
import org.threadly.litesockets.SocketExecuterInterface;
import org.threadly.litesockets.SocketExecuterInterface.WireProtocol;

public class TCPServer implements Server {
  private final ServerSocketChannel socket;
  private volatile ClientAcceptor clientAcceptor;
  private volatile ServerCloser closer;
  protected volatile Executor sei;
  protected volatile SocketExecuterInterface se;
  protected AtomicBoolean closed = new AtomicBoolean(false);

  public TCPServer(String host, int port) throws IOException {
    socket = ServerSocketChannel.open();
    socket.socket().bind(new InetSocketAddress(host, port), 100);
    socket.configureBlocking(false);
  }

  public TCPServer(ServerSocketChannel server) throws IOException{
    server.configureBlocking(false);
    socket = server;
  }

  @Override
  public void setThreadExecutor(Executor sei) {
    this.sei = sei;
  }

  @Override
  public void setSocketExecuter(SocketExecuterInterface se) {
    this.se = se;
  }

  @Override
  public SocketExecuterInterface getSocketExecuter() {
    return this.se;
  }

  @Override
  public ServerCloser getCloser() {
    return closer;
  }

  @Override
  public void setCloser(ServerCloser closer) {
    this.closer = closer;
  }

  @Override
  public ServerSocketChannel getSelectableChannel() {
    return socket;
  }

  @Override
  public void close() {
    if(closed.compareAndSet(false, true)) {
      try {
        socket.close();
      } catch (IOException e) {
        //We dont care
      } finally {
        callCloser();
      }
    }
  }

  @Override
  public WireProtocol getServerType() {
    return WireProtocol.TCP;
  }

  @Override
  public ClientAcceptor getClientAcceptor() {
    return this.clientAcceptor;
  }

  @Override
  public void setClientAcceptor(ClientAcceptor acceptor) {
    clientAcceptor = acceptor;
  }

  @Override
  public void acceptChannel(final SelectableChannel c) {
    if(sei != null ) {
      sei.execute(new Runnable() {
        public void run() {
          try {
            TCPClient client = new TCPClient((SocketChannel)c);
            if(clientAcceptor != null) {
              clientAcceptor.accept(client);
            }
          } catch (IOException e) {
            //We dont care, client closed before we could do anything with it.
          }
        }
      });
    }
  }

  protected void callCloser() {
    if(sei != null && closer != null) {
      sei.execute(new Runnable() {
        @Override
        public void run() {
          getCloser().onClose(TCPServer.this);
        }});
    }
  }
}
