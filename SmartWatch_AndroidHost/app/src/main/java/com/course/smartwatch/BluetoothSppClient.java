package com.course.smartwatch;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class BluetoothSppClient {
    public static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int READ_BUFFER_SIZE = 256;

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler mainHandler;
    private final ExecutorService connectExecutor;
    private final ExecutorService writeExecutor;
    private final ExecutorService closeExecutor;
    private final Object socketLock = new Object();
    private final Object writeLock = new Object();
    private final AtomicInteger connectionId = new AtomicInteger();

    private BluetoothSocket socket;
    private boolean connected;

    public BluetoothSppClient(BluetoothAdapter adapter, Listener listener) {
        if (adapter == null) {
            throw new IllegalArgumentException("adapter required");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener required");
        }
        this.adapter = adapter;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.connectExecutor = newSingleThreadExecutor("BluetoothSppClient-connect");
        this.writeExecutor = newSingleThreadExecutor("BluetoothSppClient-write");
        this.closeExecutor = newSingleThreadExecutor("BluetoothSppClient-close");
    }

    public Set<BluetoothDevice> getBondedDevices() {
        try {
            Set<BluetoothDevice> devices = adapter.getBondedDevices();
            return devices == null ? Collections.<BluetoothDevice>emptySet() : devices;
        } catch (SecurityException e) {
            postError("Bluetooth permission denied");
            return Collections.emptySet();
        }
    }

    public void connect(final BluetoothDevice device) {
        if (device == null) {
            postError("Bluetooth device required");
            return;
        }

        final int id = connectionId.incrementAndGet();
        closeCurrentSocketAsync();
        postState("Connecting", false, id);
        executeConnect(new Runnable() {
            @Override
            public void run() {
                connectOnWorker(device, id);
            }
        });
    }

    public void write(byte[] frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame required");
        }

        final byte[] data = Arrays.copyOf(frame, frame.length);
        final int id = connectionId.get();
        final BluetoothSocket target = currentSocketIfConnected();
        if (target == null) {
            postError("Bluetooth is not connected");
            return;
        }

        executeWrite(new Runnable() {
            @Override
            public void run() {
                writeOnWorker(target, data, id);
            }
        });
    }

    public void disconnect() {
        int id = connectionId.incrementAndGet();
        closeCurrentSocketAsync();
        postState("Disconnected", false, id);
    }

    public void shutdown() {
        int id = connectionId.incrementAndGet();
        closeCurrentSocketAsync();
        postState("Disconnected", false, id);
        connectExecutor.shutdownNow();
        writeExecutor.shutdownNow();
        closeExecutor.shutdown();
    }

    private static ExecutorService newSingleThreadExecutor(final String threadName) {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                return new Thread(runnable, threadName);
            }
        });
    }

    private void writeOnWorker(BluetoothSocket target, byte[] data, int id) {
        try {
            synchronized (writeLock) {
                if (!isCurrentSocket(target, id)) {
                    return;
                }
                OutputStream output = target.getOutputStream();
                output.write(data);
                output.flush();
            }
        } catch (IOException e) {
            closeWithError(target, id, "Bluetooth write failed", e);
        } catch (SecurityException e) {
            closeWithError(target, id, "Bluetooth write failed", e);
        }
    }

    private void connectOnWorker(BluetoothDevice device, int id) {
        BluetoothSocket newSocket = null;
        try {
            if (!isCurrent(id)) {
                return;
            }
            cancelDiscoveryIfPermitted();
            if (!isCurrent(id)) {
                return;
            }
            newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            if (!isCurrent(id)) {
                closeQuietly(newSocket);
                return;
            }
            setCurrentSocket(newSocket, false);
            if (!isCurrent(id)) {
                clearCurrentSocket(newSocket);
                closeQuietly(newSocket);
                return;
            }
            newSocket.connect();
            if (!isCurrent(id)) {
                clearCurrentSocket(newSocket);
                closeQuietly(newSocket);
                return;
            }
            markConnected(newSocket);
            postState("Connected", true, id);
            try {
                readLoop(newSocket, id);
            } catch (IOException e) {
                closeWithError(newSocket, id, "Bluetooth connection lost", e);
            }
        } catch (IOException e) {
            closeWithError(newSocket, id, "Bluetooth connection failed", e);
        } catch (SecurityException e) {
            closeWithError(newSocket, id, "Bluetooth connection failed", e);
        }
    }

    private void readLoop(BluetoothSocket activeSocket, int id) throws IOException {
        BtProtocol.Parser parser = new BtProtocol.Parser();
        InputStream input = activeSocket.getInputStream();
        byte[] buffer = new byte[READ_BUFFER_SIZE];

        while (isCurrentSocket(activeSocket, id)) {
            int count = input.read(buffer);
            if (count < 0) {
                throw new IOException("Bluetooth socket closed");
            }

            List<BtProtocol.Frame> frames = parser.feed(buffer, count);
            for (BtProtocol.Frame frame : frames) {
                postFrame(frame, id);
            }
        }
    }

    private void cancelDiscoveryIfPermitted() {
        try {
            adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
            // Android 12+ may require BLUETOOTH_SCAN. Bonded-device SPP can continue without it.
        }
    }

    private void executeConnect(Runnable runnable) {
        execute(connectExecutor, runnable, "Bluetooth client is shut down");
    }

    private void executeWrite(Runnable runnable) {
        execute(writeExecutor, runnable, "Bluetooth client is shut down");
    }

    private void execute(ExecutorService targetExecutor, Runnable runnable, String rejectedMessage) {
        try {
            targetExecutor.execute(runnable);
        } catch (RejectedExecutionException e) {
            postError(rejectedMessage);
        }
    }

    private boolean isCurrent(int id) {
        return connectionId.get() == id;
    }

    private BluetoothSocket currentSocketIfConnected() {
        synchronized (socketLock) {
            return connected ? socket : null;
        }
    }

    private boolean isCurrentSocket(BluetoothSocket expected, int id) {
        synchronized (socketLock) {
            return connectionId.get() == id && connected && socket == expected;
        }
    }

    private void setCurrentSocket(BluetoothSocket newSocket, boolean isConnected) {
        synchronized (socketLock) {
            socket = newSocket;
            connected = isConnected;
        }
    }

    private void markConnected(BluetoothSocket expected) {
        synchronized (socketLock) {
            if (socket == expected) {
                connected = true;
            }
        }
    }

    private void closeCurrentSocketAsync() {
        BluetoothSocket oldSocket;
        synchronized (socketLock) {
            oldSocket = socket;
            socket = null;
            connected = false;
        }
        closeSocketAsync(oldSocket);
    }

    private boolean clearCurrentSocket(BluetoothSocket expected) {
        if (expected == null) {
            return false;
        }
        synchronized (socketLock) {
            if (socket == expected) {
                socket = null;
                connected = false;
                return true;
            }
            return false;
        }
    }

    private void closeWithError(BluetoothSocket failedSocket, int id, String text, Exception error) {
        boolean wasCurrent = failedSocket == null || clearCurrentSocket(failedSocket);
        closeQuietly(failedSocket);
        if (wasCurrent && isCurrent(id)) {
            postError(formatError(text, error), id);
            postState("Disconnected", false, id);
        }
    }

    private void closeQuietly(BluetoothSocket socketToClose) {
        if (socketToClose == null) {
            return;
        }
        try {
            socketToClose.close();
        } catch (IOException ignored) {
            // Closing during disconnect commonly interrupts a blocking connect/read.
        }
    }

    private void closeSocketAsync(final BluetoothSocket socketToClose) {
        if (socketToClose == null) {
            return;
        }
        try {
            closeExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    closeQuietly(socketToClose);
                }
            });
        } catch (RejectedExecutionException e) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    closeQuietly(socketToClose);
                }
            }, "BluetoothSppClient-close-fallback").start();
        }
    }

    private String formatError(String text, Exception error) {
        String message = error.getMessage();
        if (message == null || message.length() == 0) {
            return text;
        }
        return text + ": " + message;
    }

    private void postState(final String text, final boolean isConnected, final int id) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrent(id)) {
                    listener.onState(text, isConnected);
                }
            }
        });
    }

    private void postFrame(final BtProtocol.Frame frame, final int id) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrent(id)) {
                    listener.onFrame(frame);
                }
            }
        });
    }

    private void postError(final String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onError(text);
            }
        });
    }

    private void postError(final String text, final int id) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrent(id)) {
                    listener.onError(text);
                }
            }
        });
    }

    public interface Listener {
        void onState(String text, boolean connected);

        void onFrame(BtProtocol.Frame frame);

        void onError(String text);
    }
}
