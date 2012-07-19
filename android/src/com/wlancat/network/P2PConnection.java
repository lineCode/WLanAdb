package com.wlancat.network;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;

import android.util.Log;

import com.google.protobuf.InvalidProtocolBufferException;
import com.wlancat.config.MyConfig;
import com.wlancat.data.CommandProto.Command;
import com.wlancat.logcat.PidsController;
import com.wlancat.utils.IOUtilities;
import com.wlancat.worker.BaseWorker;
import com.wlancat.worker.BaseWorker.WorkerListener;
import com.wlancat.worker.LogcatWorker;
import com.wlancat.worker.PushWorker;

public class P2PConnection implements Runnable, WorkerListener {
  private static final String TAG = P2PConnection.class.getSimpleName();
  private static final boolean DEBUG = MyConfig.DEBUG && true;

  public static interface ConnectionHandler {
    void onConnectionEstablished();
    boolean checkPin(String pin);
    boolean isPinRequired();
    void onConnectionClosed();
  }

  private final Socket mSocket;
  private final ConnectionHandler mConnectionHandler;

  private PidsController mPidsController;

  private Command command;
  private BaseWorker worker;

  /* Write the image to disk first. Then try to open sockets 
   * in parallel each transferring a different offset of the
   * image (e.g. split the image to 3 jobs, transfer each in
   * parallel to the others). This will workaround some TCP
   * behaviors.
   */
  protected P2PConnection(Socket socket, ConnectionHandler connectionHandler) {
    mConnectionHandler = connectionHandler;
    mSocket = socket;
    try {
      mSocket.setKeepAlive(true);
      mSocket.setTcpNoDelay(true);
    } catch (SocketException e) {
      Log.e(TAG, "Fail to set Keep-Alive to socket", e);
    }
  }

  public void setPidsController(PidsController pidsController) {
    mPidsController = pidsController;
  }

  private void close() {
    if (mSocket.isClosed())
      return;

    if (worker != null) {
      worker.stop();
      worker = null;
    }

    try {
      IOUtilities.closeStream(mSocket.getOutputStream());
    } catch (IOException e) {
      // do nothing
    }

    try{
      IOUtilities.closeStream(mSocket.getInputStream());
    } catch (IOException e) {
      // do nothing
    }

    try {
      mSocket.close();
    } catch (Exception ignore) {
      Log.e(TAG, "Fail to close Socket", ignore);
    }

    mConnectionHandler.onConnectionClosed();

    if (DEBUG)
      Log.d(TAG, "Connection was closed");
  }

  public void run() {
    mConnectionHandler.onConnectionEstablished();

    try {
      final DataInputStream mInputStream = new DataInputStream(mSocket.getInputStream());
      final int commandLength = mInputStream.readInt();

      if (DEBUG)
        Log.d(TAG, "Command size: " + commandLength);

      byte[] buffer = new byte[commandLength];
      mInputStream.read(buffer);

      try {
        Log.d(TAG, "Parsing Command...");
        command = Command.parseFrom(buffer);
        buffer = null;
      } catch (InvalidProtocolBufferException e) {
        Log.w(TAG, "Fail to parse command", e);
      }

      // we can't perform any action without specifying command.
      if (command == null) {
        close();
        return;
      }

      if (DEBUG)
        Log.d(TAG, "Command recieved:\n" + command.toString());


      if (mConnectionHandler.isPinRequired()) {
        // pin was not provided to connect with device. terminating connection.
        if (!command.hasPin()) {
          close();
          return;
        }

        // if pin is not correct. terminating connection
        if (!mConnectionHandler.checkPin(command.getPin())) {
          close();
          return;
        }
      }

      final String command = this.command.getCommand();
      if (command.equals("logcat")) {
        final LogcatWorker worker = new LogcatWorker(this.command, mSocket.getInputStream(), mSocket.getOutputStream(), this);
        worker.setPidsController(mPidsController);
        this.worker = worker;
      } else if (command.equals("push")) {
        worker = new PushWorker(this.command, mSocket.getInputStream(), mSocket.getOutputStream(), this);
      }

      if (worker != null)
        worker.start();
    } catch (IOException e) {
      Log.e(TAG, "Error while communicating with client", e);
    } finally {
      close();
    }
  }

  @Override
  public void onError() {
    close();
  }
}