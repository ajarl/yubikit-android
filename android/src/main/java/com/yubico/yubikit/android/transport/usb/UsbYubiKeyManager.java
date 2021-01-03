package com.yubico.yubikit.android.transport.usb;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.yubico.yubikit.android.transport.usb.connection.ConnectionManager;
import com.yubico.yubikit.android.transport.usb.connection.OtpConnectionHandler;
import com.yubico.yubikit.android.transport.usb.connection.SmartCardConnectionHandler;
import com.yubico.yubikit.android.transport.usb.connection.UsbOtpConnection;
import com.yubico.yubikit.android.transport.usb.connection.UsbSmartCardConnection;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

public class UsbYubiKeyManager {
    static {
        ConnectionManager.registerConnectionHandler(UsbSmartCardConnection.class, new SmartCardConnectionHandler());
        ConnectionManager.registerConnectionHandler(UsbOtpConnection.class, new OtpConnectionHandler());
    }

    private final Context context;
    private final UsbManager usbManager;
    @Nullable
    private MyDeviceListener internalListener = null;

    public UsbYubiKeyManager(Context context) {
        this.context = context;
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    /**
     * Registers receiver on usb connection event
     *
     * @param usbConfiguration contains information if device manager also registers receiver on permissions grant from user
     * @param listener         the UsbSessionListener to react to changes
     */
    public synchronized void enable(UsbConfiguration usbConfiguration, UsbYubiKeyListener listener) {
        disable();
        internalListener = new MyDeviceListener(usbConfiguration, listener);
        UsbDeviceManager.registerUsbListener(context, internalListener);
    }

    public synchronized void disable() {
        if (internalListener != null) {
            UsbDeviceManager.unregisterUsbListener(context, internalListener);
            internalListener = null;
        }
    }

    private class MyDeviceListener implements UsbDeviceManager.UsbDeviceListener {
        private final UsbYubiKeyListener listener;
        private final UsbConfiguration usbConfiguration;
        private final Map<UsbDevice, UsbYubiKeyDevice> devices = new HashMap<>();

        private MyDeviceListener(UsbConfiguration usbConfiguration, UsbYubiKeyListener listener) {
            this.usbConfiguration = usbConfiguration;
            this.listener = listener;
        }

        @Override
        public void deviceAttached(UsbDevice usbDevice) {
            UsbYubiKeyDevice yubikey = new UsbYubiKeyDevice(usbManager, usbDevice);
            devices.put(usbDevice, yubikey);
            boolean permission = usbManager.hasPermission(usbDevice);
            listener.onDeviceAttached(yubikey, permission);
            if (!permission && usbConfiguration.isHandlePermissions()) {
                UsbDeviceManager.requestPermission(context, usbDevice, (usbDevice1, hasPermission) -> {
                    synchronized (UsbYubiKeyManager.this) {
                        if (internalListener == this) {
                            listener.onRequestPermissionsResult(yubikey, hasPermission);
                        }
                    }
                });
            }
        }

        @Override
        public void deviceRemoved(UsbDevice usbDevice) {
            UsbYubiKeyDevice yubikey = devices.remove(usbDevice);
            if (yubikey != null) {
                yubikey.close();
                listener.onDeviceRemoved(yubikey);
            }
        }
    }
}
