package com.wlancat.network;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import com.wlancat.data.ClientSettings;
import com.wlancat.logcat.PidsController;

import android.util.Log;

public class P2PServer implements Runnable {
  private static final String TAG = P2PServer.class.getSimpleName();

  public interface OnConnectionsCountChanged {
    public void onConnectionsCountChanged(int connectionsCount);
  }

  private static final int MAX_CLIENTS_AT_TIME = 2;

  private final ExecutorService mClientsHandler = Executors.newFixedThreadPool(MAX_CLIENTS_AT_TIME);

  private ServerSocket mServerSocket;
  private Thread mListenThread;

  private final PidsController mPidsController;
  private final ClientSettings mClientSettings;

  private int mActiveConnections = 0;

  private OnConnectionsCountChanged mListener;

  private volatile boolean isRunning = false;

  public P2PServer(ClientSettings clientSettings, PidsController pidsController) {
    mClientSettings = clientSettings;
    this.mPidsController = pidsController;
  }

  public int start(OnConnectionsCountChanged listener) {
    mListener = listener;

    isRunning = true;

    try {
      // free port will be assigned to a socket
      mServerSocket = new ServerSocket(0);
      mServerSocket.setReuseAddress(true);
    } catch (IOException e) {
      Log.d(TAG, "Can't open server socket connection", e);
      return -1;
    }

    mListenThread = new Thread(this);
    mListenThread.start();

    return mServerSocket.getLocalPort();
  }

  public void stop() {
    mListener = null;

    isRunning = false;

    mClientsHandler.shutdownNow();

    if (mListenThread != null) {
      final Thread inherited = mListenThread;
      mListenThread = null;
      inherited.interrupt();
    }

    try {
      mServerSocket.close();
    } catch (IOException e) {
      Log.e(TAG, "Can't close server socket", e);
    }
  }

  public int getPort() {
    return mServerSocket == null ? -1 : mServerSocket.getLocalPort();
  }

  public int getActiveConnectionsCount() {
    synchronized (mConnectionHanler) {
      return mActiveConnections;
    }
  }

  public void run() {
    Log.d(TAG, "Waiting for clients connection...");

    while (isRunning) {
      final Socket socket;
      try {
        if (mServerSocket.isClosed())
          break;

        socket = mServerSocket.accept();
      } catch (SocketException e) {
        if (isRunning) {
          Log.e(TAG, "Failed to accept connection with client.", e);
          continue;
        } else {
          Log.w(TAG, "Cancel waiting for a clients because on closing connection.");
          break;
        }
      } catch (IOException e) {
        Log.e(TAG, "Failed to accept connection with client", e);
        continue;
      }

      try {
        Log.d(TAG, "New client asked for a connection");
        final P2PConnection connection = new P2PConnection(socket, mConnectionHanler);
        connection.setPidsController(mPidsController);
        mClientsHandler.execute(connection);
      } catch (RejectedExecutionException e) {
        Log.d(TAG, "There is no available slots to handle connection!");
        try {
          socket.close();
        } catch (Exception ignore) {
        }
      }
    }
  }

  private void setActiveConnectionsCount(int count) {
    synchronized (mConnectionHanler) {
      if (mActiveConnections == count)
        return;

      Log.d(TAG, "Active connections: " + count);
      mActiveConnections = count;
      mListener.onConnectionsCountChanged(mActiveConnections);
    }
  }

  private final P2PConnection.ConnectionHandler mConnectionHanler = new P2PConnection.ConnectionHandler() {
    @Override
    public void onConnectionEstablished() {
      setActiveConnectionsCount(mActiveConnections+1);
    }

    @Override
    public void onConnectionClosed() {
      setActiveConnectionsCount(mActiveConnections-1);
    }

    @Override
    public boolean checkPin(String pin) {
      return mClientSettings.checkPin(pin);
    }

    @Override
    public boolean isPinRequired() {
      return mClientSettings.hasPin();
    }
  };
}
