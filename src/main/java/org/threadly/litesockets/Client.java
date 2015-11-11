package org.threadly.litesockets;

import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.concurrent.event.ListenerHelper;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.Clock;


/**
 * <p>This is the base Client object for client communication.  Anything that reads or writes data
 * will use this object.  The clients work by having buffered reads and writes for the socket. They tell the
 * SocketExecuter when a read can be added or a write is ready for the socket.</p>
 * 
 * <p>All reads are issued on a callback on a Reader in a single threaded manor.
 * All writes will be sent in the order they are received.  In general its better to write full protocol parsable
 * packets with each write where possible.  If not possible your own locking/ordering will have to be ensured.
 * Close events will happen on there own Closer callback but it uses the same ThreadKey as the Reader thread.  
 * All Reads should be received before a close event happens.</p>
 * 
 * The client object can not function with out being in a SocketExecuter.  Writes can be added before its put
 * in the executer, but it will not write to the socket until added to the executer.
 * 
 * @author lwahlmeier
 *
 */
public abstract class Client {
  
  /**
   * SocketOptions that can be set set on Clients.
   * 
   * @author lwahlmeier
   *
   */
  public static enum SocketOption {
    TCP_NODELAY, SEND_BUFFER_SIZE, RECV_BUFFER_SIZE, UDP_FRAME_SIZE, USE_NATIVE_BUFFERS
  }
  
  /**
   * Default max buffer size (64k).  Read and write buffers are independent of each other.
   */
  protected static final int DEFAULT_MAX_BUFFER_SIZE = 65536;
  /**
   * When we hit the minimum read buffer size we will create a new one of this size (64k).
   */
  protected static final int NEW_READ_BUFFER_SIZE = 65536;
  /**
   * Minimum allowed readBuffer (4k).  If the readBuffer is lower then this we will create a new one.
   */
  protected static final int MIN_READ_BUFFER_SIZE = 4096;
  
  private final MergedByteBuffers readBuffers = new MergedByteBuffers(false);
  protected final SocketExecuter se;
  protected final long startTime = Clock.lastKnownForwardProgressingMillis();
  protected final Object readerLock = new Object();
  protected final Object writerLock = new Object();
  protected final ClientByteStats stats = new ClientByteStats();
  protected final AtomicBoolean closed = new AtomicBoolean(false);
  protected final ListenerHelper<Reader> readerListener = ListenerHelper.build(Reader.class);
  protected final ListenerHelper<CloseListener> closerListener = ListenerHelper.build(CloseListener.class);
  protected volatile boolean useNativeBuffers = false;
  protected volatile int maxBufferSize = DEFAULT_MAX_BUFFER_SIZE;
  private ByteBuffer readByteBuffer = ByteBuffer.allocate(0);
  
  public Client(final SocketExecuter se) {
    this.se = se;
  }
  
  /**
   * <p>Used by SocketExecuter to set if there was a success or error when connecting, completing the 
   * {@link ListenableFuture}.</p>
   * 
   * @param t if there was an error connecting this is provided otherwise a successful connect will pass {@code null}.
   */
  protected abstract void setConnectionStatus(Throwable t);
  
  /**
   * <p>This provides the next available Write buffer.  This is typically only called by the {@link SocketExecuter}.
   * This is not threadsafe as the same {@link ByteBuffer} will be provided to any thread that calls this, until 
   * something consumes all byte available in it at which point another {@link ByteBuffer} will be provided.</p>
   * 
   * <p>Assuming something actually removes data from the returned {@link ByteBuffer} an additional call to 
   * {@link #reduceWrite(int)} must be made</p>
   * 
   * 
   * @return a {@link ByteBuffer} that can be used to Read new data off the socket for this client.
   */
  protected abstract ByteBuffer getWriteBuffer();
  
  /**
   * <p>This is called after a write is written to the clients socket.  This tells the client how much of that 
   * {@link ByteBuffer} was written and then reduces the writeBuffersSize accordingly.</p>
   * 
   * @param size the size in bytes of data written on the socket.
   */
  protected abstract void reduceWrite(int size);
  
  /**
   * <p>Gets the raw Socket object for this Client. If the client does not have a Socket
   * it will return null (ie {@link UDPClient}). This is basically getChannel().socket()</p>
   * 
   * @return the Socket for this client.
   */
  protected abstract Socket getSocket();

  /**
   * <p>Returns the {@link SocketChannel} for this client.  If the client does not have a {@link SocketChannel} 
   * it will return null (ie {@link UDPClient}).</p>
   * 
   * @return the {@link SocketChannel}  for this client.
   */
  protected abstract SocketChannel getChannel();
  
  /**
   * 
   * @return the remote {@link SocketAddress} this client is connected to.
   */
  public abstract SocketAddress getRemoteSocketAddress();
  
  
  /**
   * 
   * @return the local {@link SocketAddress} this client is using.
   */
  public abstract SocketAddress getLocalSocketAddress();
  

  /**
   * Returns true if this client has data pending in its write buffers.  False if there is no data pending write.
   *
   * @return true if data is pending write, false if there is no data to write.
   */
  public abstract boolean canWrite();

  /**
   * <p>This tells us if the client has timed out before it has been connected to the socket.  
   * If the client has connected fully this will return false from that point on (even on a closed connection).</p>
   * 
   * @return false if the client has been connected, true if it has not connected and the timeout limit has been reached.
   */
  public abstract boolean hasConnectionTimedOut();
  
  /**
   * <p>This lets you set lower level socket options for this client.  Mainly Buffer sizes and no delay options.</p>
   * 
   * @param so The {@link SocketOption} to set for the client.
   * @param value The value for the socket option (1 for on, 0 for off).
   * @return True if the option was set, false if not.
   */
  public abstract boolean setSocketOption(SocketOption so, int value);
  
  /**
   * 
   * <p>Called to connect this client to a host.  This is done non-blocking.</p>
   * 
   * <p>If there is an error connecting {@link #close()} will also be called on the client.</p>
   * 
   * @return A {@link ListenableFuture} that will complete when the socket is connected, or fail if we cant connect.
   */
  public abstract ListenableFuture<Boolean> connect();
  
  /**
   * Sets the connection timeout value for this client.  This must be called before {@link #connect()} has called on this client.  
   * 
   * @param timeout the time in milliseconds to wait for the client to connect.
   */
  public abstract void setConnectionTimeout(int timeout);
  
  /**
   * <p>Used to get this clients connection timeout information.</p>
   * 
   * @return the max amount of time to wait for a connection to connect on this socket.
   */
  public abstract int getTimeout();
  
  
  /**
   * <p>This is used to get the current size of the unWriten writeBuffer.</p>
   * 
   * @return the current size of the writeBuffer.
   */
  public abstract int getWriteBufferSize();
  
  /**
   * <p>This is used by the {@link SocketExecuter} to help understand how to manage this client.
   * Currently only UDP and TCP exist.</p>
   * 
   * @return The IP protocol type of this client.
   */
  public abstract WireProtocol getProtocol();
  
  /**
   * <p>This is called to write data to the clients socket.  Its important to note that there is no back
   * pressure when adding writes so care should be taken to now allow the clients {@link #getWriteBufferSize()} to get
   * to big.</p>
   * 
   * @param bb The {@link ByteBuffer} to write onto the clients socket. 
   * @return A {@link ListenableFuture} that will be completed once the data has been fully written to the socket.
   */
  public abstract ListenableFuture<?> write(ByteBuffer bb);
  
  /**
   * <p>Closes this client.  Reads can still occur after this it called.  {@link CloseListener#onClose(Client)} will still be
   * called (if set) once all reads are done.</p>
   */
  public abstract void close();
  
  
  protected void addReadStats(final int size) {
    stats.addRead(size);
  }
  
  protected void addWriteStats(final int size) {
    stats.addWrite(size);
  }

  /**
   * <p>When this clients socket has a read pending and {@link #canRead()} is true, this is where the ByteBuffer for the read comes from.
   * In general this should only be used by the ReadThread in the {@link SocketExecuter} and it should be noted 
   * that it is not threadsafe.</p>
   * 
   * @return A {@link ByteBuffer} to use during this clients read operations.
   */
  protected ByteBuffer provideReadByteBuffer() {
    if(readByteBuffer.remaining() < MIN_READ_BUFFER_SIZE) {
      if(useNativeBuffers) {
        readByteBuffer = ByteBuffer.allocateDirect(DEFAULT_MAX_BUFFER_SIZE);
      } else {
        readByteBuffer = ByteBuffer.allocate(DEFAULT_MAX_BUFFER_SIZE);
      }
    }
    return readByteBuffer;
  }
  
  /**
   * <p>This is used to get the currently set {@link Closer} for this client.</p>
   * 
   * @return the current {@link Closer} interface for this client.
   */
  protected void callClosers() {
    this.closerListener.call().onClose(this);
  }
  
  protected void callReader() {
    this.readerListener.call().onRead(this);
  }
  
  /**
   * 
   * <p>Adds a {@link ByteBuffer} to the Clients readBuffer.  This is normally only used by the {@link SocketExecuter},
   * though it could be used to artificially inject data through the client.  Calling this will schedule a
   * calling the currently set Reader on the client.</p>
   * 
   * 
   * @param bb the {@link ByteBuffer} to add to the clients readBuffer.
   */
  protected void addReadBuffer(final ByteBuffer bb) {
    addReadStats(bb.remaining());
    synchronized(readerLock) {
      final int start = readBuffers.remaining();
      readBuffers.add(bb);
      if(this.readerListener.registeredListenerCount() > 0 && readBuffers.remaining() > 0 && start == 0){
        callReader();
      }
    }
  }
  
  /**
   * Returns true if this client can have reads added to it or false if its read buffers are full.
   * 

   * @return true if more reads can be added, false if not.
   */
  public boolean canRead() {
    return readBuffers.remaining() < maxBufferSize;
  }
  
  /**
   * <p>This is used to get the current size of the readBuffers pending reads.</p>
   * 
   * @return the current size of the ReadBuffer.
   */
  public int getReadBufferSize() {
    return readBuffers.remaining();
  }
  
  /**
   * This is used to get the currently set max buffer size.
   * 
   * @return the current MaxBuffer size allowed.  The read and write buffer use this independently.
   */
  public int getMaxBufferSize() {
    return this.maxBufferSize;
  }
  
  /**
   * <p> This returns this clients {@link Executor}.</p>
   * 
   * <p> Its worth noting that operations done on this {@link Executor} can/will block Read callbacks on the 
   * client, but it does provide you the ability to execute things on the clients read thread.</p>
   * 
   * @return The {@link Executor} for the client.
   */
  public Executor getClientsThreadExecutor() {
    return se.getExecutorFor(this);
  }
  
  /**
   * <p>This is used to get the clients {@link SocketExecuter}.</p>
   * 
   * @return the {@link SocketExecuter} set for this client. if none, null is returned.
   */
  public SocketExecuter getClientsSocketExecuter() {
    return se;
  }

  /**
   * <p>This adds a {@link CloseListener} for this client.  Once set the client will call .onClose 
   * on it once it a socket close is detected.</p> 
   * 
   * @param closer sets this clients {@link CloseListener} callback.
   */
  public void addCloseListener(final CloseListener closer) {
    if(closed.get()) {
      getClientsThreadExecutor().execute(new Runnable() {
        @Override
        public void run() {
          closer.onClose(Client.this);
        }});      
    } else {
      closerListener.addListener(closer, this.getClientsThreadExecutor());
    }
  }

  /**
   * <p>This sets the Reader for the client.  Once set data received on the socket will be callback 
   * on this Reader to be processed.  If no reader is set before connecting the client read data will just
   * queue up till we hit the {@link #getMaxBufferSize()} size, then will be flushed to the first Reader added.</p>
   * 
   * @param reader the {@link Reader} callback to set for this client.
   */
  public void setReader(final Reader reader) {
    if(! closed.get()) {
      readerListener.clearListeners();
      if(reader != null) {
        readerListener.addListener(reader, this.getClientsThreadExecutor());
        synchronized(readerLock) {
          if(this.getReadBufferSize() > 0) {
            readerListener.call().onRead(this);
          }
        }
      }
    }
  }

  /**
   * <p>This allows you to set/change the max buffer size for this client object.
   * This is the in java memory buffer not the additional socket buffer the OS might setup.</p>
   * 
   * <p>In general this should be set to the max size you can deal with.  The lower this is the more often we
   * will end up adding/removing the client from the selectors.  Only the read buffer really follows this, 
   * writes will buffer up as much as you let it.  You need room in your heap for buffers for all clients.
   * This buffer is not kept at full size, so clients will rarely use that much memory assuming the the protocol parsing 
   * and network are keeping up with the data going in/out.</p>
   * 
   * @param size max buffer size in bytes.
   */
  public void setMaxBufferSize(final int size) {
    ArgumentVerifier.assertGreaterThanZero(size, "size");
    maxBufferSize = size;
  }
  
  /**
   * <p>Whenever a the {@link Reader} Interfaces {@link Reader#onRead(Client)} is called the
   * {@link #getRead()} should be called from the client.</p>
   * 
   * @return a {@link MergedByteBuffers} of the current read data for this client.
   */
  public MergedByteBuffers getRead() {
    MergedByteBuffers mbb = null;
    synchronized(readerLock) {
      mbb = readBuffers.duplicateAndClean();
    }
    if(mbb.remaining() >= maxBufferSize) {
      se.setClientOperations(this);
    }
    return mbb;
  }
  
  /**
   * <p>Returns if this client is closed or not.  Once a client is marked closed there is no way to reOpen it.
   * You must just make a new client.  Just because this returns false does not mean the client is connected.
   * Before a client connects, but has not yet closed this will be false.</p>
   * 
   * @return true if the client is closed, false if the client has not yet been closed.
   */
  public boolean isClosed() {
    return closed.get();
  }
  
  protected boolean setClose() {
    return closed.compareAndSet(false, true);
  }
  
  /**
   * Returns the {@link SimpleByteStats} for this client.
   * 
   * @return the byte stats for this client.
   */
  public SimpleByteStats getStats() {
    return stats;
  }
  
  /**
   * Implementation of the SimpleByteStats.
   */
  private static class ClientByteStats extends SimpleByteStats {
    public ClientByteStats() {
      super();
    }

    @Override
    protected void addWrite(final int size) {
      ArgumentVerifier.assertNotNegative(size, "size");
      super.addWrite(size);
    }
    
    @Override
    protected void addRead(final int size) {
      ArgumentVerifier.assertNotNegative(size, "size");
      super.addRead(size);
    }
  }
  
  /**
   * Used to notify when a Client there is data to Read for a Client.
   * 
   * <p>Any client with this set will call .onRead(client) when a read is read from the socket.
   * These will happen in a single threaded manor for that client.  The thread used is the clients
   * thread and should not be blocked for long.  If it is the client will back up and stop reading until
   * it is unblocked.</p>
   * 
   * <p>The implementor of Reader should call {@link Client#getRead()}.</p>
   * 
   * <p>If the same Reader is used by multiple clients each client will call the Reader on its own thread so be careful 
   * what objects your modifying if you do that.</p>
   * 
   * 
   *
   */
  public interface Reader {
    /**
     * <p>When this callback is called it will pass you the Client object
     * that did the read.  .getRead() should be called on once and only once
     * before returning.  If it is failed to be called or called more then once
     * you could end up with uncalled data on the wire or getting a null.</p>
     * 
     * @param client This is the client the read is being called for.
     */
    public void onRead(Client client);
  }
  
  
  /**
   * Used to notify when a Client is closed.
   * 
   * <p>It is also single threaded on the same thread key as the reads.
   * No action must be taken when this is called but it is usually advised to do some kind of clean up or something
   * as this client object will no longer be used for anything.</p>
   * 
   */
  public interface CloseListener {
    /**
     * This notifies the callback about the client being closed.
     * 
     * @param client This is the client the close is being called for.
     */
    public void onClose(Client client);
  }  
}

