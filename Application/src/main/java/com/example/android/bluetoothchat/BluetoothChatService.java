/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.bluetoothchat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;

import com.example.android.common.logger.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static android.content.Context.BLUETOOTH_SERVICE;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for
 * incoming connections, a thread for connecting with a device, and a
 * thread for performing data transmissions when connected.
 */
public class BluetoothChatService {
    // Debugging
    private static final String TAG = "BluetoothChatService";

    // Name for the SDP record when creating server socket
    private static final String NAME_SECURE = "BluetoothChatSecure";
    private static final String NAME_INSECURE = "BluetoothChatInsecure";

    // Unique UUID for this application
    private static final UUID MY_UUID_SECURE =
            UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
    private static final UUID MY_UUID_INSECURE =
            UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");

    // Member fields
    private final BluetoothAdapter mAdapter;
    private BluetoothManager mBluetoothManager;
    private BluetoothGattServer mBluetoothGattServer;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private Set<BluetoothDevice> mRegisteredDevices = new HashSet<>();
    private Context mContext;

    private final Handler mHandler;
    private AcceptThread mSecureAcceptThread;
    private AcceptThread mInsecureAcceptThread;
    private ConnectThread mConnectThread;
    private ArrayList<ConnectedThread> mConnectedThreads = new ArrayList<>();
    private int mState;
    private int mNewState;

    public static final int LEMODE_NO = 0;
    public static final int LEMODE_SERVER = 1;
    public static final int LEMODE_CLIENT = 2;

    private int mLEMode = LEMODE_NO;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

    /**
     * Constructor. Prepares a new BluetoothChat session.
     *
     * @param context The UI Activity Context
     * @param handler A Handler to send messages back to the UI Activity
     */
    public BluetoothChatService(Context context, Handler handler) {
        mBluetoothManager = (BluetoothManager) context.getSystemService(BLUETOOTH_SERVICE);
//        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAdapter = mBluetoothManager.getAdapter();
        mState = STATE_NONE;
        mNewState = mState;
        mHandler = handler;
        mContext = context;
    }

    /**
     * Update UI title according to the current state of the chat connection
     */
    private synchronized void updateUserInterfaceTitle() {
        mState = getState();
        Log.d(TAG, "updateUserInterfaceTitle() " + mNewState + " -> " + mState);
        mNewState = mState;

        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, mNewState, -1).sendToTarget();
    }

    /**
     * Return the current connection state.
     */
    public synchronized int getState() {
        return mState;
    }


    /*
     * BLE server side handling, pretending to be a BLE device
     */

    // new GATT Server
    BluetoothGattCharacteristic mCharacteristic;

    public void createGattServer() {
        mLEMode = LEMODE_SERVER;

        BluetoothGattService service = new BluetoothGattService(Constants.CHAT_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        mCharacteristic = new BluetoothGattCharacteristic(Constants.CHAT_CHARACTER,
                //Read-only characteristic, supports notifications
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE          // allow client to send us message
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
                        | BluetoothGattCharacteristic.PERMISSION_WRITE
        );

        // optional. A descriptor
        BluetoothGattDescriptor configDescriptor = new BluetoothGattDescriptor(Constants.CHAT_DESCRIPTOR,
                //Read/write descriptor
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        mCharacteristic.addDescriptor(configDescriptor);

        service.addCharacteristic(mCharacteristic);

        //2.5 打开外围设备  注意这个services和server的区别，别记错了
        mBluetoothGattServer = mBluetoothManager.openGattServer(mContext, mGattServerCallback);
        if (mBluetoothGattServer == null) {
            return;
        }
        mBluetoothGattServer.addService(service);
    }

    public void startAdvertising() {
        mBluetoothLeAdvertiser = mAdapter.getBluetoothLeAdvertiser();
        if (mBluetoothLeAdvertiser == null) {
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(Constants.CHAT_SERVICE))//绑定服务uuid
                .build();
        mBluetoothLeAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);
    }

    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i("", "LE Advertise Started.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w("", "LE Advertise Failed: " + errorCode);
        }
    };

    /**
     * Callback to handle incoming requests to the GATT server.
     */
    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            //连接状态改变
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mState = STATE_CONNECTED;
                // Send the name of the connected device back to the UI Activity
                Message msg = mHandler.obtainMessage(Constants.MESSAGE_DEVICE_NAME);
                Bundle bundle = new Bundle();

                // name is always null, maybe the one initiating connection is never passing its own name
                bundle.putString(Constants.DEVICE_NAME, "BLES " + device.getName());
                msg.setData(bundle);
                mHandler.sendMessage(msg);
                // Update UI title
                updateUserInterfaceTitle();


                Log.i("", "BluetoothDevice CONNECTED: " + device);
                mRegisteredDevices.add(device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mState = STATE_NONE;
                // Update UI title
                updateUserInterfaceTitle();

                Log.i("", "BluetoothDevice DISCONNECTED: " + device);
                mRegisteredDevices.remove(device);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            //请求读特征 如果包含有多个服务，就要区分请求读的是什么，这里我只有一个服务
            if (Constants.CHAT_CHARACTER.equals(characteristic.getUuid())) {
                //回应
                mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, "TO Be filled out text msg".getBytes());
            }

        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            if (Constants.CHAT_DESCRIPTOR.equals(descriptor.getUuid())) {
                Log.d("", "Config descriptor read");
                byte[] returnValue;
                if (mRegisteredDevices.contains(device)) {
                    returnValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                } else {
                    returnValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                }
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        returnValue);
            } else {
                Log.w("", "Unknown descriptor read request");
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null);
            }

        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (Constants.CHAT_DESCRIPTOR.equals(descriptor.getUuid())) {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    Log.d("", "Subscribe device to notifications: " + device);
                    mRegisteredDevices.add(device);
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                    Log.d("", "Unsubscribe device from notifications: " + device);
                    mRegisteredDevices.remove(device);
                }

                if (responseNeeded) {
                    mBluetoothGattServer.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null);
                }
            } else {
                Log.w("", "Unknown descriptor write request");
                if (responseNeeded) {
                    mBluetoothGattServer.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null);
                }
            }
        }

        // mechanism to write data back
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (characteristic.getUuid().equals(Constants.CHAT_CHARACTER)) {
                mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);

                // Send the obtained bytes to the UI Activity
                mHandler.obtainMessage(Constants.MESSAGE_READ, value.length, -1, value)
                        .sendToTarget();
            }
        }
    };







    /*
     *  BLE client side handling
     */

    public void startScanLeDevice() {
        mAdapter.startLeScan(new UUID[]{Constants.CHAT_SERVICE}, leScanCallback);
    }

    public void stopScanLeDevice() {
        mAdapter.stopLeScan(leScanCallback);
    }

    private BluetoothDevice mLeDevice;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mClientCharacteristic;
    // Device scan callback.
    private BluetoothAdapter.LeScanCallback leScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi,
                                     byte[] scanRecord) {

                    // TODO, this should handle multiple devices in the future
                    mLeDevice = device;

                    mBluetoothGatt = device.connectGatt(mContext, false, gattCallback);

                    // just need one device right now, without this, it seems to get in repeated disconnect state
                    stopScanLeDevice();
                }
            };


    // Various callback methods defined by the BLE API.
    private final BluetoothGattCallback gattCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                                    int newState) {
                    String intentAction;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        mState = STATE_CONNECTED;
                        mLEMode = LEMODE_CLIENT;

                        // Send the name of the connected device back to the UI Activity
                        Message msg = mHandler.obtainMessage(Constants.MESSAGE_DEVICE_NAME);
                        Bundle bundle = new Bundle();
                        bundle.putString(Constants.DEVICE_NAME, "BLEC " + mLeDevice.getName());
                        msg.setData(bundle);
                        mHandler.sendMessage(msg);
                        // Update UI title
                        updateUserInterfaceTitle();

                        Log.i(TAG, "Connected to GATT server.");
                        Log.i(TAG, "Attempting to start service discovery:" +
                                mBluetoothGatt.discoverServices());

                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i(TAG, "Disconnected from GATT server.");

                        mState = STATE_NONE;
                        // Update UI title
                        updateUserInterfaceTitle();

//                        mBluetoothGatt.disconnect();
//                        mBluetoothGatt.close();
//                        mBluetoothGatt = null;

                        // may need to disable notification
                    }
                }

                @Override
                // New services discovered
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {

                        mClientCharacteristic =
                                gatt.getService(Constants.CHAT_SERVICE)
                                        .getCharacteristic(Constants.CHAT_CHARACTER);


                        // enable notification
                        mClientCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                        boolean initialized = gatt.setCharacteristicNotification(mClientCharacteristic, true);







//                        BluetoothGattDescriptor descriptor =
//                                mClientCharacteristic.getDescriptor(Constants.CHAT_DESCRIPTOR);
//
//                        descriptor.setValue(
//                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
//                        gatt.writeDescriptor(descriptor);
//
//
//                        // heart rate
//                        mClientCharacteristic.setValue(new byte[]{1, 1});
//                        gatt.writeCharacteristic(mClientCharacteristic);
//
//                        gatt.readCharacteristic(mClientCharacteristic);

                    } else {
                        Log.w(TAG, "onServicesDiscovered received: " + status);
                    }
                }

                @Override
                // Result of a characteristic read operation
                public void onCharacteristicRead(BluetoothGatt gatt,
                                                 BluetoothGattCharacteristic characteristic,
                                                 int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "read " + characteristic.getValue());

//                        Log.i("onCharacteristicRead", characteristic.toString());
//                        byte[] value=characteristic.getValue();
//                        String v = new String(value);
//                        Log.i("onCharacteristicRead", "Value: " + v);
                    }
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {

                    byte[] messageBytes = characteristic.getValue();
                    String messageString = null;
                    try {
                        messageString = new String(messageBytes, "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        Log.e(TAG, "Unable to convert message bytes to string");
                    }

                    // Send the obtained bytes to the UI Activity
                    mHandler.obtainMessage(Constants.MESSAGE_READ, messageBytes.length, -1, messageBytes)
                            .sendToTarget();

                    Log.d(TAG, "read value " + messageString);
                }
    };




    // Classic Bluetooth handling
    /**
     * Start the chat service. Specifically start AcceptThread to begin a
     * session in listening (server) mode. Called by the Activity onResume()
     */
    public synchronized void start() {
        Log.d(TAG, "start");

        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        for (ConnectedThread t : mConnectedThreads)
            t.cancel();
        mConnectedThreads.clear();

        // Start the thread to listen on a BluetoothServerSocket
        if (mSecureAcceptThread == null) {
            mSecureAcceptThread = new AcceptThread(true);
            mSecureAcceptThread.start();
        }
        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = new AcceptThread(false);
            mInsecureAcceptThread.start();
        }
        // Update UI title
        updateUserInterfaceTitle();
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     *
     * @param device The BluetoothDevice to connect
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    public synchronized void connect(BluetoothDevice device, boolean secure) {
        Log.d(TAG, "connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        // Cancel any thread currently running a connection
//        if (mConnectedThread != null) {
//            mConnectedThread.cancel();
//            mConnectedThread = null;
//        }

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device, secure);
        mConnectThread.start();
        // Update UI title
        updateUserInterfaceTitle();
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     *
     * @param socket The BluetoothSocket on which the connection was made
     * @param device The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice
            device, final String socketType) {
        mLEMode = LEMODE_NO;

        Log.d(TAG, "connected, Socket Type:" + socketType);

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
//        if (mConnectedThread != null) {
//            mConnectedThread.cancel();
//            mConnectedThread = null;
//        }

        // DO NOT cancel the accept thread because we want to connect to more than one device
//        if (mSecureAcceptThread != null) {
//            mSecureAcceptThread.cancel();
//            mSecureAcceptThread = null;
//        }
//        if (mInsecureAcceptThread != null) {
//            mInsecureAcceptThread.cancel();
//            mInsecureAcceptThread = null;
//        }

        // Start the thread to manage the connection and perform transmissions
        ConnectedThread nt = new ConnectedThread(socket, socketType);
        nt.mDevice = device;
        nt.start();
        // add to list, we could have more than 1 thread available
        mConnectedThreads.add(nt);

        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();

        String deviceNames = mConnectedThreads.size() + " ";
        for (ConnectedThread t : mConnectedThreads)
            deviceNames += t.mDevice.getName() + ",";

        bundle.putString(Constants.DEVICE_NAME, deviceNames);
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        // Update UI title
        updateUserInterfaceTitle();
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {
        Log.d(TAG, "stop");

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        for (ConnectedThread t : mConnectedThreads)
            t.cancel();
        mConnectedThreads.clear();

        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }

        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }
        mState = STATE_NONE;
        // Update UI title
        updateUserInterfaceTitle();

        if (mBluetoothLeAdvertiser!=null) {
            mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
        }
        if(mBluetoothGattServer!=null) {
            mBluetoothGattServer.close();
        }

    }

    private String mMsg;
    /**
     * Write to the ConnectedThread in an unsynchronized manner
     * Also, overloaded to handle BLE communications too
     *
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write(byte[] out) {
        // Synchronize a copy of the ConnectedThread
        if (mLEMode == LEMODE_SERVER) {
            mMsg = new String(out);
            mCharacteristic.setValue(mMsg);
            for (BluetoothDevice device : mRegisteredDevices) {
                mBluetoothGattServer.notifyCharacteristicChanged(device, mCharacteristic, false);
            }
        }
        else if (mLEMode == LEMODE_CLIENT) {

            mClientCharacteristic.setValue(out);
            boolean success = mBluetoothGatt.writeCharacteristic( mClientCharacteristic );
        }
        else {
            for (ConnectedThread r : mConnectedThreads) {
                synchronized (this) {
                    if (mState != STATE_CONNECTED) return;
                }
                // Perform the write unsynchronized
                r.write(out);
            }
        }

        // Share the sent message back to the UI Activity
        mHandler.obtainMessage(Constants.MESSAGE_WRITE, -1, -1, out)
                .sendToTarget();

    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailed() {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        if (mConnectedThreads.size() == 0)
            mState = STATE_LISTEN;      // listen thread is always on
        else
            mState = STATE_CONNECTED;
        // Update UI title
        updateUserInterfaceTitle();

        // Start the service over to restart listening mode
//        BluetoothChatService.this.start();
        // should keep it alive,
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void connectionLost(ConnectedThread thread) {
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        mConnectedThreads.remove(thread);
        if (mConnectedThreads.size() == 0)
            mState = STATE_LISTEN;          // listen thread is always on
        else
            mState = STATE_CONNECTED;
        // Update UI title
        updateUserInterfaceTitle();

        // should always be in listening mode
        // Start the service over to restart listening mode
//        BluetoothChatService.this.start();
    }

    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private class AcceptThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;
        private String mSocketType;

        public AcceptThread(boolean secure) {
            BluetoothServerSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            // Create a new listening server socket
            try {
                if (secure) {
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE,
                            MY_UUID_SECURE);
                } else {
                    tmp = mAdapter.listenUsingInsecureRfcommWithServiceRecord(
                            NAME_INSECURE, MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: " + mSocketType + "listen() failed", e);
            }
            mmServerSocket = tmp;
            mState = STATE_LISTEN;
        }

        public void run() {
            Log.d(TAG, "Socket Type: " + mSocketType +
                    "BEGIN mAcceptThread" + this);
            setName("AcceptThread" + mSocketType);

            BluetoothSocket socket = null;

            // always listen to connections
            while (mState != STATE_NONE) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "Socket Type: " + mSocketType + "accept() failed", e);
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (BluetoothChatService.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                            case STATE_CONNECTED:
                                // Situation normal. Start the connected thread.
                                connected(socket, socket.getRemoteDevice(),
                                        mSocketType);
                                break;
                            case STATE_NONE:
                                // Either not ready or already connected. Terminate new socket.
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Could not close unwanted socket", e);
                                }
                                break;
                        }
                    }
                }
            }
            Log.i(TAG, "END mAcceptThread, socket Type: " + mSocketType);

        }

        public void cancel() {
            Log.d(TAG, "Socket Type" + mSocketType + "cancel " + this);
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Socket Type" + mSocketType + "close() of server failed", e);
            }
        }
    }


    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private String mSocketType;

        public ConnectThread(BluetoothDevice device, boolean secure) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                if (secure) {
                    tmp = device.createRfcommSocketToServiceRecord(
                            MY_UUID_SECURE);
                } else {
                    tmp = device.createInsecureRfcommSocketToServiceRecord(
                            MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: " + mSocketType + "create() failed", e);
            }
            mmSocket = tmp;
            mState = STATE_CONNECTING;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectThread SocketType:" + mSocketType);
            setName("ConnectThread" + mSocketType);

            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() " + mSocketType +
                            " socket during connection failure", e2);
                }
                Log.i(TAG, "Exception " + e.getMessage());
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothChatService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice, mSocketType);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect " + mSocketType + " socket failed", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        public BluetoothDevice mDevice;

        public ConnectedThread(BluetoothSocket socket, String socketType) {
            Log.d(TAG, "create ConnectedThread: " + socketType);
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
            mState = STATE_CONNECTED;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            byte[] buffer = new byte[1024];
            int bytes;

            // Keep listening to the InputStream while connected
            while (mState == STATE_CONNECTED) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);

                    // Send the obtained bytes to the UI Activity
                    mHandler.obtainMessage(Constants.MESSAGE_READ, bytes, -1, buffer)
                            .sendToTarget();

                    // also forward to other connections to simulate multihop routing
                    // this should be executing in our current thread
                    // This will not generate IOException, which will cause this connection to die
                   for (ConnectedThread t : mConnectedThreads) {
                        if (t != this) {
                            // do not reuse the buffer
                            byte[] copy = buffer.clone();
                            t.write(copy);
                        }
                    }

                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost(this);
                    break;
                }
            }
        }

        /**
         * Write to the connected OutStream.
         *
         * @param buffer The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}
