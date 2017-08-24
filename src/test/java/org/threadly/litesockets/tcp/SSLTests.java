package org.threadly.litesockets.tcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.ClientCloseListener;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.Server;
import org.threadly.litesockets.Server.ClientAcceptor;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.TCPClient;
import org.threadly.litesockets.TCPServer;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.buffers.MergedByteBuffers;
import org.threadly.litesockets.buffers.ReuseableMergedByteBuffers;
import org.threadly.litesockets.utils.PortUtils;
import org.threadly.litesockets.utils.SSLUtils;
import org.threadly.test.concurrent.TestCondition;
import org.threadly.util.ExceptionUtils;

public class SSLTests {
  PriorityScheduler PS;
  int port;
  final String GET = "hello";
  SocketExecuter SE;
  TrustManager[] myTMs = new TrustManager [] {new SSLUtils.FullTrustManager() };
  KeyStore KS;
  KeyManagerFactory kmf;
  SSLContext sslCtx;
  FakeTCPServerClient serverFC;
  
  @Before
  public void start() throws Exception {
    PS = new PriorityScheduler(5);
    SE = new ThreadedSocketExecuter(PS);
    SE.start();
    port = PortUtils.findTCPPort();
    KS = KeyStore.getInstance(KeyStore.getDefaultType());

    File filename = new File(ClassLoader.getSystemClassLoader().getResource("test.pem").getFile());

    kmf = SSLUtils.generateKeyStoreFromPEM(filename, filename);    
    sslCtx = SSLContext.getInstance("SSL");
    sslCtx.init(kmf.getKeyManagers(), myTMs, null);
    serverFC = new FakeTCPServerClient();
  }
  
  @After
  public void stop() {
    for(Server s: serverFC.getAllServers()) {
      s.close();
    }
    
    for(Client c: serverFC.getAllClients()) {
      c.close();
    }
    SE.stop();
    PS.shutdownNow();
    serverFC = new FakeTCPServerClient();
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  
  @Test(expected=IllegalStateException.class)
  public void badSSLStart() throws Exception {
    TCPServer server = SE.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    server.setDoHandshake(true);    
    serverFC.addTCPServer(server);

    final TCPClient client = SE.createTCPClient("localhost", port);

    client.startSSL();
  }
  
  @Test
  public void failSSLonPlainConnectionWrite() throws Exception {
    final SettableListenableFuture<Boolean> gotError = new SettableListenableFuture<Boolean>();
    TCPServer server = SE.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    server.setDoHandshake(true);
    serverFC.addTCPServer(server);
    
    final TCPClient client = SE.createTCPClient("localhost", port);
    client.connect().get();
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getAllClients().size() == 1;
      }
    }.blockTillTrue(5000);
    
    serverFC.getAllClients().get(0).addCloseListener(new ClientCloseListener() {

      @Override
      public void onClose(Client client) {

      }
      
      @Override
      public void onCloseWithError(Client client, Throwable error) {
        gotError.setResult(true);
      }  
    });
    
    client.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    
    new TestCondition(){
      @Override
      public boolean get() {
        return SE.getClientCount() == 0;
      }
    }.blockTillTrue(5000);
    assertTrue(gotError.get());
  }
  
  @Test
  public void simpleWriteTest() throws Exception {
    long start = System.currentTimeMillis();
    TCPServer server = SE.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    server.setDoHandshake(true);    
    serverFC.addTCPServer(server);
    
    final TCPClient client = SE.createTCPClient("localhost", port);
    SSLEngine sslec = sslCtx.createSSLEngine("localhost", port);
    sslec.setUseClientMode(true);
    client.setSSLEngine(sslec);
    serverFC.addTCPClient(client);
    client.connect().get(5000, TimeUnit.MILLISECONDS);
    client.startSSL().get(5000, TimeUnit.MILLISECONDS);;
    
    System.out.println(System.currentTimeMillis()-start);
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 2;
      }
    }.blockTillTrue(5000);
    final TCPClient sclient = serverFC.getClientAt(1);
    new TestCondition(){
      @Override
      public boolean get() {
        return client.isEncrypted();
      }
    }.blockTillTrue(5000);
    assertTrue(client.isEncrypted());
    assertTrue(sclient.isEncrypted());
    System.out.println("Write");
    sclient.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    System.out.println("Write Done");
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getClientsBuffer(client).remaining() > 2;
      }
    }.blockTillTrue(5000);
    
    String st = serverFC.getClientsBuffer(client).getAsString(serverFC.getClientsBuffer(client).remaining());
    assertEquals(TCPTests.SMALL_TEXT, st);
  }

  @Test
  public void sslClientTimeout() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    TCPServer server = SE.createTCPServer("localhost", port);
    serverFC.addTCPServer(server);
    long start = System.currentTimeMillis();
    try {
      final TCPClient client = SE.createTCPClient("localhost", port);
      SSLEngine ssle = sslCtx.createSSLEngine("localhost", port);
      ssle.setUseClientMode(true);
      client.setSSLEngine(ssle);
      client.setConnectionTimeout(201);
      client.connect();
      client.startSSL().get(5000, TimeUnit.MILLISECONDS);
      fail();
    } catch(CancellationException e) {
      assertTrue(System.currentTimeMillis()-start >= 200);
    }
    server.close();
  }

  @Test
  public void largeWriteTest() throws Exception{
    
    TCPServer server = SE.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    server.setSSLHostName("localhost");
    server.setDoHandshake(true);
    serverFC.addTCPServer(server);
    
    final TCPClient client = SE.createTCPClient("localhost", port);
    SSLEngine sslec = sslCtx.createSSLEngine("localhost", port);
    sslec.setUseClientMode(true);
    client.setSSLEngine(sslec);
    serverFC.addTCPClient(client);
    client.startSSL();

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 2 && client.isEncrypted();
      }
    }.blockTillTrue(5000);
    final TCPClient sclient = serverFC.getClientAt(1);

    serverFC.addTCPClient(client);
    
    for(int i=0; i<3; i++) {
      sclient.write(TCPTests.LARGE_TEXT_BUFFER.duplicate());
    }
    
    new TestCondition(){
      @Override
      public boolean get() {
        /*
        System.out.println(serverFC.map.get(client).remaining()+":"+(TCPTests.LARGE_TEXT_BUFFER.remaining()*3));
        System.out.println("w:"+sclient.finishedHandshake.get()+":"+sclient.startedHandshake.get());
        System.out.println("w:"+sclient.ssle.getHandshakeStatus());
        System.out.println("r:"+client.ssle.getHandshakeStatus());
        System.out.println("r:"+client.getReadBufferSize());
        */
        if(serverFC.getClientsBuffer(client) != null) {
          return serverFC.getClientsBuffer(client).remaining() == TCPTests.LARGE_TEXT_BUFFER.remaining()*3;
        }
        return false;
      }
    }.blockTillTrue(5000);
    
    String st = serverFC.getClientsBuffer(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
    st = serverFC.getClientsBuffer(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
    st = serverFC.getClientsBuffer(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
  }
    
//  @Test(expected=IllegalStateException.class)
//  public void useTCPClientPendingReads() throws IOException {
//    TCPServer server = SE.createTCPServer("localhost", port);
//    serverFC.addTCPServer(server);
//    
//    final TCPClient tcp_client = SE.createTCPClient("localhost", port);
//    //serverFC.addTCPClient(tcp_client);
//    SE.addClient(tcp_client);
//    tcp_client.setReader(new Reader() {
//      @Override
//      public void onRead(Client client) {
//        System.out.println("GOT READ");
//        //We do nothing here
//      }});
//    
//    new TestCondition(){
//      @Override
//      public boolean get() {
//        return serverFC.clients.size() == 1;
//      }
//    }.blockTillTrue(5000);
//    TCPClient sclient = (TCPClient) serverFC.clients.get(0);
//
//    sclient.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
//    
//    new TestCondition(){
//      @Override
//      public boolean get() {
//        return tcp_client.getReadBufferSize() > 0;
//      }
//    }.blockTillTrue(5000);
//    
//    final SSLClient client = new SSLClient(tcp_client, this.sslCtx.createSSLEngine("localhost", port), true, true);
//    client.close();
//  }
  
//  @Test
//  public void loop() throws Exception {
//    for(int i=0; i<100; i++) {
//      this.doLateSSLhandshake();
//      stop();
//      start();
//    }
//  }
  
  @Test
  public void doLateSSLhandshake() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    TCPServer server = SE.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    server.setSSLHostName("localhost");
    server.setDoHandshake(false);

    final AtomicReference<TCPClient> servers_client = new AtomicReference<TCPClient>();
    final AtomicReference<String> serversEncryptedString = new AtomicReference<String>();
    final AtomicReference<String> clientsEncryptedString = new AtomicReference<String>();
    
    server.setClientAcceptor(new ClientAcceptor() {
      @Override
      public void accept(Client c) {
        final TCPClient sslc = (TCPClient) c;
        servers_client.set(sslc);
        sslc.setReader(new Reader() {
          MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
          boolean didSSL = false;
          @Override
          public void onRead(Client client) {
            mbb.add(client.getRead());
            if(!didSSL && mbb.remaining() >= 6) {
              String tmp = mbb.getAsString(6);
              if(tmp.equals("DO_SSL")) {
                sslc.write(ByteBuffer.wrap("DO_SSL".getBytes()));
                System.out.println("DOSSL-Server");
                sslc.startSSL().addListener(new Runnable() {
                  @Override
                  public void run() {
                    didSSL = true;
                    System.out.println("DIDSSL-Server");
                  }});
              }
            } else {
              if(mbb.remaining() >= 19) {
                String tmp = mbb.getAsString(19);
                serversEncryptedString.set(tmp);
                client.write(ByteBuffer.wrap("THIS WAS ENCRYPTED!".getBytes()));
              }
            }
          }});
          //SE.addClient(sslc.getTCPClient());
      }});
    server.start();
    
    final TCPClient sslclient = SE.createTCPClient("localhost", port);
    SSLEngine sslec = sslCtx.createSSLEngine("localhost", port);
    sslec.setUseClientMode(true);
    sslclient.setSSLEngine(sslec);

    sslclient.setReader(new Reader() {
      MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
      boolean didSSL = false;
      @Override
      public void onRead(Client client) {
        mbb.add(client.getRead());
        if(!didSSL && mbb.remaining() >= 6) {
          String tmp = mbb.getAsString(6);
          if(tmp.equals("DO_SSL")) {
            System.out.println("DOSSL");
            sslclient.startSSL().addListener(new Runnable() {
              @Override
              public void run() {
                didSSL = true;
                sslclient.write(ByteBuffer.wrap("THIS WAS ENCRYPTED!".getBytes()));
                System.out.println("DIDSSL"); 
              }});

          }
        } else {
          if(mbb.remaining() >= 19) {
            String tmp = mbb.getAsString(19);
            clientsEncryptedString.set(tmp);
          }
        }
      }});
    System.out.println(sslclient);
    try {
      sslclient.connect().get(5000, TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    System.out.println("WRITE!!");
    try {
      sslclient.write(ByteBuffer.wrap("DO_SSL".getBytes())).get(5000, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      System.out.println("WRITE ERROR!! "+sslclient.getWriteBufferSize());
      throw e;
    }
    System.out.println("WRITE DONE!!");
    
    new TestCondition(){
      @Override
      public boolean get() {
//        if(servers_client.get() != null) {
//          System.out.println(servers_client.get().getReadBufferSize());
//        }
        return clientsEncryptedString.get() != null && serversEncryptedString.get() != null;
      }
    }.blockTillTrue(5000);
    assertEquals(clientsEncryptedString.get(), serversEncryptedString.get());
  }
}
