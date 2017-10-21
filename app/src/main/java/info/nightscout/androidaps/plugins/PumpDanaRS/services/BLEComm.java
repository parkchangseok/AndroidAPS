package info.nightscout.androidaps.plugins.PumpDanaRS.services;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.cozmo.danar.util.BleCommandUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.events.EventPumpStatusChanged;
import info.nightscout.androidaps.plugins.PumpDanaR.DanaRPump;
import info.nightscout.androidaps.plugins.PumpDanaRS.DanaRSPlugin;
import info.nightscout.androidaps.plugins.PumpDanaRS.activities.PairingHelperActivity;
import info.nightscout.androidaps.plugins.PumpDanaRS.activities.PairingProgressDialog;
import info.nightscout.androidaps.plugins.PumpDanaRS.comm.DanaRSMessageHashTable;
import info.nightscout.androidaps.plugins.PumpDanaRS.comm.DanaRS_Packet;
import info.nightscout.androidaps.plugins.PumpDanaRS.events.EventDanaRSPacket;
import info.nightscout.androidaps.plugins.PumpDanaRS.events.EventDanaRSPairingSuccess;
import info.nightscout.utils.SP;

/**
 * Created by mike on 23.09.2017.
 */

public class BLEComm {
    private static Logger log = LoggerFactory.getLogger(BLEComm.class);

    private static final long WRITE_DELAY_MILLIS = 50;

    public static String UART_READ_UUID = "0000fff1-0000-1000-8000-00805f9b34fb";
    public static String UART_WRITE_UUID = "0000fff2-0000-1000-8000-00805f9b34fb";

    private byte PACKET_START_BYTE = (byte) 0xA5;
    private byte PACKET_END_BYTE = (byte) 0x5A;
    private static BLEComm instance = null;

    public static BLEComm getInstance(DanaRSService service) {
        if (instance == null)
            instance = new BLEComm(service);
        return instance;
    }

    private Object mConfirmConnect = null;

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> scheduledDisconnection = null;

    private DanaRS_Packet processsedMessage = null;
    private ArrayList<byte[]> mSendQueue = new ArrayList<>();

    // Variables for connection progress (elapsed time)
    private Handler sHandler;
    private HandlerThread sHandlerThread;
    private long connectionStartTime = 0;
    private final Runnable updateProgress = new Runnable() {
        @Override
        public void run() {
            long secondsElapsed = (System.currentTimeMillis() - connectionStartTime) / 1000;
            MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.CONNECTING, (int) secondsElapsed));
            sHandler.postDelayed(updateProgress, 1000);
        }
    };

    private BluetoothManager mBluetoothManager = null;
    private BluetoothAdapter mBluetoothAdapter = null;
    private BluetoothDevice mBluetoothDevice = null;
    private String mBluetoothDeviceAddress = null;
    private String mBluetoothDeviceName = null;
    private BluetoothGatt mBluetoothGatt = null;

    protected boolean isConnected = false;
    protected boolean isConnecting = false;

    private BluetoothGattCharacteristic UART_Read;
    private BluetoothGattCharacteristic UART_Write;

    private DanaRSService service;

    BLEComm(DanaRSService service) {
        this.service = service;
        initialize();

        if (sHandlerThread == null) {
            sHandlerThread = new HandlerThread(PairingProgressDialog.class.getSimpleName());
            sHandlerThread.start();
            sHandler = new Handler(sHandlerThread.getLooper());
        }
    }

    private boolean initialize() {
        log.debug("Initializing BLEComm.");

        if (mBluetoothManager == null) {
            mBluetoothManager = ((BluetoothManager) MainApp.instance().getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE));
            if (mBluetoothManager == null) {
                log.debug("Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            log.debug("Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public boolean isConnecting() {
        return isConnecting;
    }

    public boolean connect(String from, String address, Object confirmConnect) {
        mConfirmConnect = confirmConnect;
        BluetoothManager tBluetoothManager = ((BluetoothManager) MainApp.instance().getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE));
        if (tBluetoothManager == null) {
            return false;
        }

        BluetoothAdapter tBluetoothAdapter = tBluetoothManager.getAdapter();
        if (tBluetoothAdapter == null) {
            return false;
        }

        if (mBluetoothAdapter == null) {
            if (!initialize()) {
                return false;
            }
        }

        if (address == null) {
            log.debug("unspecified address.");
            return false;
        }

        connectionStartTime = System.currentTimeMillis();

        MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.CONNECTING));
        isConnecting = true;

        // Following should be removed later because we close Gatt on disconnect and this should never happen
        if ((mBluetoothDeviceAddress != null) && (address.equals(mBluetoothDeviceAddress)) && (mBluetoothGatt != null)) {
            log.debug("Trying to use an existing mBluetoothGatt for connection.");
            sHandler.post(updateProgress);
            if (mBluetoothGatt.connect()) {
                setCharacteristicNotification(getUARTReadBTGattChar(), true);
                return true;
            }
            sHandler.removeCallbacks(updateProgress);
            return false;
        }
        // end

        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            log.debug("Device not found.  Unable to connect.");
            return false;
        }

        sHandler.post(updateProgress);
        mBluetoothGatt = device.connectGatt(service.getApplicationContext(), false, mGattCallback);
        setCharacteristicNotification(getUARTReadBTGattChar(), true);
        log.debug("Trying to create a new connection.");
        mBluetoothDevice = device;
        mBluetoothDeviceAddress = address;
        mBluetoothDeviceName = device.getName();
        return true;
    }

    public void stopConnecting() {
        isConnecting = false;
        sHandler.removeCallbacks(updateProgress); // just to be sure
    }

    public void disconnect(String from) {
        log.debug("disconnect from: " + from);
        if ((mBluetoothAdapter == null) || (mBluetoothGatt == null)) {
            return;
        }
        setCharacteristicNotification(getUARTReadBTGattChar(), false);
        mBluetoothGatt.disconnect();
        isConnected = false;
    }

    public void close() {
        log.debug("BluetoothAdapter close");
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    public BluetoothDevice getConnectDevice() {
        return mBluetoothDevice;
    }

    public String getConnectDeviceAddress() {
        return mBluetoothDeviceAddress;
    }

    public String getConnectDeviceName() {
        return mBluetoothDeviceName;
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            log.debug("onConnectionStateChange");

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mBluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                close();
                isConnected = false;
                sHandler.removeCallbacks(updateProgress); // just to be sure
                MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTED));
                log.debug("Device was disconnected " + gatt.getDevice().getName());//Device was disconnected
            }
        }

        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            log.debug("onServicesDiscovered");

            isConnecting = false;

            if (status == BluetoothGatt.GATT_SUCCESS) {
                findCharacteristic();
            }
            // stop sending connection progress
            sHandler.removeCallbacks(updateProgress);
            SendPumpCheck();
            // 1st message sent to pump after connect
        }

        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            log.debug("onCharacteristicRead" + (characteristic != null ? ":" + DanaRS_Packet.toHexString(characteristic.getValue()) : ""));
            addToReadBuffer(characteristic.getValue());
            readDataParsing();
        }

        public void onCharacteristicChanged(BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
            log.debug("onCharacteristicChanged" + (characteristic != null ? ":" + DanaRS_Packet.toHexString(characteristic.getValue()) : ""));
            addToReadBuffer(characteristic.getValue());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    readDataParsing();
                }
            }).start();
        }

        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            log.debug("onCharacteristicWrite" + (characteristic != null ? ":" + DanaRS_Packet.toHexString(characteristic.getValue()) : ""));
            new Thread(new Runnable() {
                @Override
                public void run() {
                    synchronized (mSendQueue) {
                        // after message sent, check if there is the rest of the message waiting and send it
                        if (mSendQueue.size() > 0) {
                            byte[] bytes = mSendQueue.get(0);
                            mSendQueue.remove(0);
                            writeCharacteristic_NO_RESPONSE(getUARTWriteBTGattChar(), bytes);
                        }
                    }
                }
            }).start();
        }
    };

    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        log.debug("setCharacteristicNotification");
        if ((mBluetoothAdapter == null) || (mBluetoothGatt == null)) {
            log.debug("BluetoothAdapter not initialized_ERROR");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);
    }

    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        log.debug("readCharacteristic");
        if ((mBluetoothAdapter == null) || (mBluetoothGatt == null)) {
            log.debug("BluetoothAdapter not initialized_ERROR");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    public void writeCharacteristic_NO_RESPONSE(final BluetoothGattCharacteristic characteristic, final byte[] data) {
        if ((mBluetoothAdapter == null) || (mBluetoothGatt == null)) {
            log.debug("BluetoothAdapter not initialized_ERROR");
            return;
        }

        new Thread(new Runnable() {
            public void run() {
                SystemClock.sleep(WRITE_DELAY_MILLIS);
                characteristic.setValue(data);
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                log.debug("writeCharacteristic:" + DanaRS_Packet.toHexString(data));
                mBluetoothGatt.writeCharacteristic(characteristic);
            }
        }).start();
    }

    public BluetoothGattCharacteristic getUARTReadBTGattChar() {
        if (UART_Read == null) {
            UART_Read = new BluetoothGattCharacteristic(UUID.fromString(UART_READ_UUID), BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
        }
        return UART_Read;
    }

    public BluetoothGattCharacteristic getUARTWriteBTGattChar() {
        if (UART_Write == null) {
            UART_Write = new BluetoothGattCharacteristic(UUID.fromString(UART_WRITE_UUID), BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, 0);
        }
        return UART_Write;
    }

    public List<BluetoothGattService> getSupportedGattServices() {
        log.debug("getSupportedGattServices");
        if ((mBluetoothAdapter == null) || (mBluetoothGatt == null)) {
            log.debug("BluetoothAdapter not initialized_ERROR");
            return null;
        }

        return mBluetoothGatt.getServices();
    }

    private void findCharacteristic() {
        List<BluetoothGattService> gattServices = getSupportedGattServices();

        if (gattServices == null) {
            return;
        }
        String uuid = null;

        for (BluetoothGattService gattService : gattServices) {
            List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                uuid = gattCharacteristic.getUuid().toString();
                if (UART_READ_UUID.equals(uuid)) {
                    UART_Read = gattCharacteristic;
                    setCharacteristicNotification(UART_Read, true);
                }
                if (UART_WRITE_UUID.equals(uuid)) {
                    UART_Write = gattCharacteristic;
                }
            }
        }
    }

    private byte[] readBuffer = new byte[1024];
    private int bufferLength = 0;

    private void addToReadBuffer(byte[] buffer) {
        //log.debug("addToReadBuffer " + DanaRS_Packet.toHexString(buffer));
        if (buffer == null || buffer.length == 0) {
            return;
        }
        synchronized (readBuffer) {
            // Append incomming data to input buffer
            System.arraycopy(buffer, 0, readBuffer, bufferLength, buffer.length);
            bufferLength += buffer.length;
        }
    }

    private void readDataParsing() {
        boolean startSignatureFound = false, packetIsValid = false;
        boolean isProcessing;

        isProcessing = true;

        while (isProcessing) {
            int length = 0;
            byte[] inputBuffer = null;
            synchronized (readBuffer) {
                // Find packet start [A5 A5]
                if (bufferLength >= 6) {
                    for (int idxStartByte = 0; idxStartByte < bufferLength - 2; idxStartByte++) {
                        if ((readBuffer[idxStartByte] == PACKET_START_BYTE) && (readBuffer[idxStartByte + 1] == PACKET_START_BYTE)) {
                            if (idxStartByte > 0) {
                                // if buffer doesn't start with signature remove the leading trash
                                log.debug("Shifting the input buffer by " + idxStartByte + " bytes");
                                System.arraycopy(readBuffer, idxStartByte, readBuffer, 0, bufferLength - idxStartByte);
                                bufferLength -= idxStartByte;
                            }
                            startSignatureFound = true;
                            break;
                        }
                    }
                }
                // A5 A5 LEN TYPE CODE PARAMS CHECKSUM1 CHECKSUM2 5A 5A
                //           ^---- LEN -----^
                // total packet length 2 + 1 + readBuffer[2] + 2 + 2
                if (startSignatureFound) {
                    length = readBuffer[2];
                    // test if there is enough data loaded
                    if (length + 7 > bufferLength)
                        return;
                    // Verify packed end [5A 5A]
                    if ((readBuffer[length + 5] == PACKET_END_BYTE) && (readBuffer[length + 6] == PACKET_END_BYTE)) {
                        packetIsValid = true;
                    }
                }
                if (packetIsValid) {
                    inputBuffer = new byte[length + 7];
                    // copy packet to input buffer
                    System.arraycopy(readBuffer, 0, inputBuffer, 0, length + 7);
                    // Cut off the message from readBuffer
                    try {
                        System.arraycopy(readBuffer, length + 7, readBuffer, 0, bufferLength - (length + 7));
                    } catch (Exception e) {
                        log.debug("length: " + length + "bufferLength: " + bufferLength);
                        throw e;
                    }
                    bufferLength -= (length + 7);
                    // now we have encrypted packet in inputBuffer
                }
            }
            if (packetIsValid) {
                try {
                    // decrypt the packet
                    inputBuffer = BleCommandUtil.getInstance().getDecryptedPacket(inputBuffer);

                    if (inputBuffer == null) {
                        log.debug("Null decryptedInputBuffer");
                        return;
                    }

                    switch (inputBuffer[0]) {
                        // initial handshake packet
                        case (byte) BleCommandUtil.DANAR_PACKET__TYPE_ENCRYPTION_RESPONSE:
                            switch (inputBuffer[1]) {
                                // 1st packet
                                case (byte) BleCommandUtil.DANAR_PACKET__OPCODE_ENCRYPTION__PUMP_CHECK:
                                    if (inputBuffer.length == 4 && inputBuffer[2] == 'O' && inputBuffer[3] == 'K') {
                                        log.debug("<<<<< " + "ENCRYPTION__PUMP_CHECK (OK)" + " " + DanaRS_Packet.toHexString(inputBuffer));
                                        // Grab pairing key from preferences if exists
                                        String pairingKey = SP.getString(MainApp.sResources.getString(R.string.key_danars_pairingkey) + DanaRSPlugin.mDeviceName, null);
                                        log.debug("Using stored pairing key: " + pairingKey);
                                        if (pairingKey != null) {
                                            byte[] encodedPairingKey = DanaRS_Packet.hexToBytes(pairingKey);
                                            byte[] bytes = BleCommandUtil.getInstance().getEncryptedPacket(BleCommandUtil.DANAR_PACKET__OPCODE_ENCRYPTION__CHECK_PASSKEY, encodedPairingKey, null);
                                            log.debug(">>>>> " + "ENCRYPTION__CHECK_PASSKEY" + " " + DanaRS_Packet.toHexString(bytes));
                                            writeCharacteristic_NO_RESPONSE(getUARTWriteBTGattChar(), bytes);
                                        } else {
                                            // Stored pairing key does not exists, request pairing
                                            SendPairingRequest();
                                        }

                                    } else if (inputBuffer.length == 6 && inputBuffer[2] == 'B' && inputBuffer[3] == 'U' && inputBuffer[4] == 'S' && inputBuffer[5] == 'Y') {
                                        log.debug("<<<<< " + "ENCRYPTION__PUMP_CHECK (BUSY)" + " " + DanaRS_Packet.toHexString(inputBuffer));
                                        mSendQueue.clear();
                                        MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTED, MainApp.sResources.getString(R.string.pumpbusy)));
                                    } else {
                                        log.debug("<<<<< " + "ENCRYPTION__PUMP_CHECK (ERROR)" + " " + DanaRS_Packet.toHexString(inputBuffer));
                                        mSendQueue.clear();
                                        MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.DISCONNECTED, MainApp.sResources.getString(R.string.connectionerror)));
                                    }
                                    break;
                                // 2nd packet, pairing key
                                case (byte) BleCommandUtil.DANAR_PACKET__OPCODE_ENCRYPTION__CHECK_PASSKEY:
                                    log.debug("<<<<< " + "ENCRYPTION__CHECK_PASSKEY" + " " + DanaRS_Packet.toHexString(inputBuffer));
                                    if (inputBuffer[2] == (byte) 0x00) {
                                        // Paring is not requested, sending time info
                                        SendTimeInfo();
                                    } else {
                                        // Pairing on pump is requested
                                        SendPairingRequest();
                                    }
                                    break;
                                case (byte) BleCommandUtil.DANAR_PACKET__OPCODE_ENCRYPTION__PASSKEY_REQUEST:
                                    log.debug("<<<<< " + "ENCRYPTION__PASSKEY_REQUEST " + DanaRS_Packet.toHexString(inputBuffer));
                                    if (inputBuffer[2] != (byte) 0x00) {
                                        disconnect("passkey request failed");
                                    }
                                    break;
                                // Paring response, OK button on pump pressed
                                case (byte) BleCommandUtil.DANAR_PACKET__OPCODE_ENCRYPTION__PASSKEY_RETURN:
                                    log.debug("<<<<< " + "ENCRYPTION__PASSKEY_RETURN " + DanaRS_Packet.toHexString(inputBuffer));
                                    // Paring is successfull, sending time info
                                    MainApp.bus().post(new EventDanaRSPairingSuccess());
                                    SendTimeInfo();
                                    byte[] pairingKey = {inputBuffer[2], inputBuffer[3]};
                                    // store pairing key to preferences
                                    SP.putString(MainApp.sResources.getString(R.string.key_danars_pairingkey) + DanaRSPlugin.mDeviceName, DanaRS_Packet.bytesToHex(pairingKey));
                                    log.debug("Got pairing key: " + DanaRS_Packet.bytesToHex(pairingKey));
                                    break;
                                // time and user password information. last packet in handshake
                                case (byte) BleCommandUtil.DANAR_PACKET__OPCODE_ENCRYPTION__TIME_INFORMATION:
                                    log.debug("<<<<< " + "ENCRYPTION__TIME_INFORMATION " + /*message.getMessageName() + " " + */ DanaRS_Packet.toHexString(inputBuffer));
                                    int size = inputBuffer.length;
                                    int pass = ((inputBuffer[size - 1] & 0x000000FF) << 8) + ((inputBuffer[size - 2] & 0x000000FF));
                                    pass = pass ^ 3463;
                                    DanaRPump.getInstance().rs_password = Integer.toHexString(pass);
                                    log.debug("Pump user password: " + Integer.toHexString(pass));
                                    MainApp.bus().post(new EventPumpStatusChanged(EventPumpStatusChanged.CONNECTED));

                                    isConnected = true;
                                    isConnecting = false;
                                    service.getPumpStatus();
                                    scheduleDisconnection();
                                    if (mConfirmConnect != null) {
                                        synchronized (mConfirmConnect) {
                                            mConfirmConnect.notify();
                                            mConfirmConnect = null;
                                        }
                                    }
                                    break;
                            }
                            break;
                        // common data packet
                        default:
                            DanaRS_Packet message;
                            // Retrieve message code from received buffer and last message sent
                            int originalCommand = processsedMessage != null ? processsedMessage.getCommand() : 0xFFFF;
                            int receivedCommand = DanaRS_Packet.getCommand(inputBuffer);
                            if (originalCommand == receivedCommand) {
                                // it's response to last message
                                message = processsedMessage;
                            } else {
                                // it's not response to last message, create new instance
                                message = DanaRSMessageHashTable.findMessage(receivedCommand);
                            }
                            if (message != null) {
                                log.debug("<<<<< " + message.getFriendlyName() + " " + DanaRS_Packet.toHexString(inputBuffer));
                                // process received data
                                message.handleMessage(inputBuffer);
                                message.setReceived();
                                synchronized (message) {
                                    // notify to sendMessage
                                    message.notify();
                                }
                                MainApp.bus().post(new EventDanaRSPacket(message));
                            } else {
                                log.error("Unknown message received " + DanaRS_Packet.toHexString(inputBuffer));
                            }
                            scheduleDisconnection();
                            break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                startSignatureFound = false;
                packetIsValid = false;
                if (bufferLength < 6) {
                    // stop the loop
                    isProcessing = false;
                }
            } else {
                // stop the loop
                isProcessing = false;
            }
        }
    }

    public void sendMessage(DanaRS_Packet message) {
        processsedMessage = message;
        if (message == null)
            return;

        byte[] command = {(byte) message.getType(), (byte) message.getOpCode()};
        byte[] params = message.getRequestParams();
        log.debug(">>>>> " + message.getFriendlyName() + " " + DanaRS_Packet.toHexString(command) + " " + DanaRS_Packet.toHexString(params));
        byte[] bytes = BleCommandUtil.getInstance().getEncryptedPacket(message.getOpCode(), params, null);
        // If there is another message not completely sent, add to queue only
        if (mSendQueue.size() > 0) {
            // Split to parts per 20 bytes max
            for (; ; ) {
                if (bytes.length > 20) {
                    byte[] addBytes = new byte[20];
                    System.arraycopy(bytes, 0, addBytes, 0, addBytes.length);
                    byte[] reBytes = new byte[bytes.length - addBytes.length];
                    System.arraycopy(bytes, addBytes.length, reBytes, 0, reBytes.length);
                    bytes = reBytes;
                    synchronized (mSendQueue) {
                        mSendQueue.add(addBytes);
                    }
                } else {
                    synchronized (mSendQueue) {
                        mSendQueue.add(bytes);
                    }
                    break;
                }
            }

        } else {
            if (bytes.length > 20) {
                // Cut first 20 bytes
                byte[] sendBytes = new byte[20];
                System.arraycopy(bytes, 0, sendBytes, 0, sendBytes.length);
                byte[] reBytes = new byte[bytes.length - sendBytes.length];
                System.arraycopy(bytes, sendBytes.length, reBytes, 0, reBytes.length);
                bytes = reBytes;
                // and send
                writeCharacteristic_NO_RESPONSE(getUARTWriteBTGattChar(), sendBytes);
                // The rest split to parts per 20 bytes max
                for (; ; ) {
                    if (bytes.length > 20) {
                        byte[] addBytes = new byte[20];
                        System.arraycopy(bytes, 0, addBytes, 0, addBytes.length);
                        reBytes = new byte[bytes.length - addBytes.length];
                        System.arraycopy(bytes, addBytes.length, reBytes, 0, reBytes.length);
                        bytes = reBytes;
                        synchronized (mSendQueue) {
                            mSendQueue.add(addBytes);
                        }
                    } else {
                        synchronized (mSendQueue) {
                            mSendQueue.add(bytes);
                        }
                        break;
                    }
                }
            } else {
                writeCharacteristic_NO_RESPONSE(getUARTWriteBTGattChar(), bytes);
            }
        }
        // The rest from queue is send from onCharasteristicWrite (after sending 1st part)
        synchronized (message) {
            try {
                message.wait(5000);
            } catch (InterruptedException e) {
                log.error("sendMessage InterruptedException", e);
                e.printStackTrace();
            }
        }

        //SystemClock.sleep(200);
        if (!message.isReceived()) {
            log.warn("Reply not received " + message.getFriendlyName());
        }
        scheduleDisconnection();
    }

    private void SendPairingRequest() {
        // Start activity which is waiting 20sec
        // On pump pairing request is displayed and is waiting for conformation
        Intent i = new Intent();
        i.setClass(MainApp.instance(), PairingHelperActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainApp.instance().startActivity(i);

        byte[] bytes = BleCommandUtil.getInstance().getEncryptedPacket(BleCommandUtil.DANAR_PACKET__OPCODE_ENCRYPTION__PASSKEY_REQUEST, null, null);
        log.debug(">>>>> " + "ENCRYPTION__PASSKEY_REQUEST" + " " + DanaRS_Packet.toHexString(bytes));
        writeCharacteristic_NO_RESPONSE(getUARTWriteBTGattChar(), bytes);
    }

    protected void SendPumpCheck() {
        // 1st message sent to pump after connect
        byte[] bytes = BleCommandUtil.getInstance().getEncryptedPacket(BleCommandUtil.DANAR_PACKET__OPCODE_ENCRYPTION__PUMP_CHECK, null, getConnectDeviceName());
        log.debug(">>>>> " + "ENCRYPTION__PUMP_CHECK (0x00)" + " " + DanaRS_Packet.toHexString(bytes));
        writeCharacteristic_NO_RESPONSE(getUARTWriteBTGattChar(), bytes);
    }

    private void SendTimeInfo() {
        byte[] bytes = BleCommandUtil.getInstance().getEncryptedPacket(BleCommandUtil.DANAR_PACKET__OPCODE_ENCRYPTION__TIME_INFORMATION, null, null);
        log.debug(">>>>> " + "ENCRYPTION__TIME_INFORMATION" + " " + DanaRS_Packet.toHexString(bytes));
        writeCharacteristic_NO_RESPONSE(getUARTWriteBTGattChar(), bytes);
    }

    public void scheduleDisconnection() {
        class DisconnectRunnable implements Runnable {
            public void run() {
                disconnect("scheduleDisconnection");
                scheduledDisconnection = null;
            }
        }
        // prepare task for execution in 5 sec
        // cancel waiting task to prevent sending multiple disconnections
        if (scheduledDisconnection != null)
            scheduledDisconnection.cancel(false);
        Runnable task = new DisconnectRunnable();
        final int sec = 5;
        scheduledDisconnection = worker.schedule(task, sec, TimeUnit.SECONDS);
        log.debug("Disconnection scheduled");
    }

}