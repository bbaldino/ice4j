package org.ice4j.socket;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by brian on 3/9/17.
 */
public class MultiplexingDatagramSocket
  extends SafeCloseDatagramSocket {

  private final List<DatagramPacket> received
      = new SocketReceiveBuffer()
  {
    private static final long serialVersionUID
        = 3125772367019091216L;

    @Override
    public int getReceiveBufferSize()
        throws SocketException
    {
      return MultiplexingDatagramSocket.this.getReceiveBufferSize();
    }
  };

  Object receiveLock = new Object();
  boolean doingReceive;
  private boolean setReceiveBufferSize = false;
  private int receiveBufferSize;



  private int soTimeout = 0;

  private final List<MultiplexedDatagramSocket> sockets = new ArrayList<>();

  public MultiplexingDatagramSocket()
    throws SocketException
  {
  }

  public MultiplexingDatagramSocket(DatagramSocket delegate)
    throws SocketException
  {
    super(delegate);
  }

  public MultiplexingDatagramSocket(int port, InetAddress laddr)
      throws SocketException
  {
    super(port, laddr);
  }

  public MultiplexingDatagramSocket(SocketAddress bindaddr)
      throws SocketException
  {
    super(bindaddr);
  }

  void close(MultiplexedDatagramSocket multiplexed)
  {
    synchronized (sockets)
    {
      sockets.remove(multiplexed);
    }
  }

  public MultiplexedDatagramSocket getSocket(DatagramPacketFilter filter)
      throws SocketException
  {
    return getSocket(filter, /* create */ true);
  }

  public MultiplexedDatagramSocket getSocket(
      DatagramPacketFilter filter,
      boolean create)
      throws SocketException
  {
    // find or create a socket with the given filter
    synchronized(sockets)
    {
      for (MultiplexedDatagramSocket socket : sockets)
      {
        if (filter.equals(socket.getFilter()))
        {
          return socket;
        }
      }
      if (!create)
      {
        return null;
      }
      MultiplexedDatagramSocket socket = new MultiplexedDatagramSocket(this, filter);
      sockets.add(socket);
      return socket;
    }
  }

  @Override
  public int getSoTimeout()
  {
    return soTimeout;
  }

  private void receiveHelper(List<DatagramPacket> received, DatagramPacket p, int timeout)
      throws IOException
  {
    long startTime = System.currentTimeMillis();
    DatagramPacket r = null;

    do
    {
      long now = System.currentTimeMillis();
      synchronized (received)
      {
        if (!received.isEmpty())
        {
          r = received.remove(0);
          if (r != null)
          {
            break;
          }
        }
      }

      long remainingTimeout;
      if (soTimeout > 0)
      {
        remainingTimeout = soTimeout - (now - startTime);
        if (remainingTimeout <= 0L)
        {
          throw new SocketTimeoutException(Long.toString(remainingTimeout));
        }
      }
      else
      {
        remainingTimeout = 1000L;
      }

      boolean wait;
      synchronized(receiveLock)
      {
        if (doingReceive)
        {
          wait = true;
        }
        else
        {
          wait = false;
          doingReceive = true;
        }
      }
      try
      {
        if (wait)
        {
          synchronized(received)
          {
            if (received.isEmpty())
            {
              try
              {
                received.wait(remainingTimeout);
              } catch (InterruptedException e)
              {
              }
            }
            else
            {
              received.notifyAll();;
            }
          }
          continue;
        }

        DatagramPacket c = MultiplexingXXXSocketSupport.clone(p, false);
        synchronized(receiveLock)
        {
          if (setReceiveBufferSize)
          {
            setReceiveBufferSize = false;
            try
            {
              super.setReceiveBufferSize(receiveBufferSize);
            } catch (Throwable t)
            {
              if (t instanceof ThreadDeath)
              {
                throw (ThreadDeath)t;
              }
            }
          }
        }
        super.receive(c);
        acceptBySocketsOrThis(c);
      }
      finally
      {
        synchronized(receiveLock)
        {
          if (!wait)
          {
            doingReceive = false;
          }
        }
      }
    } while(true);
    MultiplexingXXXSocketSupport.copy(r, p);
  }

  private void acceptBySocketsOrThis(DatagramPacket p)
  {
    synchronized (sockets)
    {
      boolean accepted = false;

      for (MultiplexedDatagramSocket socket : sockets)
      {
        if (socket.getFilter().accept(p))
        {
          List<DatagramPacket> socketReceived = socket.received;

          synchronized (socketReceived)
          {
            socketReceived.add(
                accepted ? MultiplexingXXXSocketSupport.clone(p, /* arraycopy */ true) : p);
            socketReceived.notifyAll();
          }
          accepted = true;

          // Emil Ivov: Don't break because we want all
          // filtering sockets to get the received packet.
        }
      }
      if (!accepted)
      {
        List<DatagramPacket> thisReceived = received;

        synchronized (thisReceived)
        {
          thisReceived.add(p);
          thisReceived.notifyAll();
        }
      }
    }
  }

  @Override
  public void receive(DatagramPacket p)
      throws IOException
  {
    receiveHelper(received, p, soTimeout);
  }

  void receive(MultiplexedDatagramSocket multiplexed, DatagramPacket p)
      throws IOException
  {
    receiveHelper(multiplexed.received, p, soTimeout);
  }

  @Override
  public void setReceiveBufferSize(int receiveBufferSize)
      throws SocketException
  {
    synchronized (receiveLock)
    {
      this.receiveBufferSize = receiveBufferSize;
      if (doingReceive)
      {
        setReceiveBufferSize = true;
      }
      else
      {
        super.setReceiveBufferSize(receiveBufferSize);
        setReceiveBufferSize = false;
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setSoTimeout(int timeout)
      throws SocketException
  {
    super.setSoTimeout(timeout);

    soTimeout = timeout;
  }
}
