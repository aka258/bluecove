/**
 * BlueCove BlueZ module - Java library for Bluetooth on Linux
 *  Copyright (C) 2008 Mark Swanson
 *  Copyright (C) 2008 Vlad Skarzhevskyy
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 *
 * @version $Id$
 */
package com.intel.bluetooth;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.Vector;

import javax.bluetooth.BluetoothStateException;
import javax.bluetooth.DataElement;
import javax.bluetooth.DeviceClass;
import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.DiscoveryListener;
import javax.bluetooth.RemoteDevice;
import javax.bluetooth.ServiceRecord;
import javax.bluetooth.ServiceRegistrationException;
import javax.bluetooth.UUID;

import org.bluez.Adapter;
import org.bluez.Manager;
import org.bluez.Error.Failed;
import org.bluez.Error.NoSuchAdapter;
import org.bluez.Error.NotReady;
import org.freedesktop.dbus.DBusConnection;
import org.freedesktop.dbus.DBusSigHandler;
import org.freedesktop.dbus.UInt32;
import org.freedesktop.dbus.exceptions.DBusException;

import cx.ath.matthew.unix.UnixSocket;

/**
 * A Java/DBUS implementation. Property "bluecove.deviceID" or
 * "bluecove.deviceAddress" can be used to select Local Bluetooth device.
 * 
 * bluecove.deviceID: String HCI ID. ID e.g. hci0, hci1, hci2, etc.
 * bluecove.deviceID: String Device number. e.g. 0, 1, 2, etc.
 * bluecove.deviceAddress: String in JSR-82 format.
 * 
 * Please help with these questions:
 * 
 * 0. I note that Adapter.java has a bunch of methods commented out. Do you feel
 * these aren't needed to get a bare bones implementation working? I notice that
 * getLocalDeviceDiscoverable() could use adapter.getMode() "discoverable"
 * though I have no idea how to convert that to an int return value... 1.
 * 
 * A: The idea was that I copied all the method descriptors from bluez-d-bus
 * documentation. Some I tested and this is uncommented. Some I'm not sure are
 * implemented as described so I commented out.
 */
class BluetoothStackBlueZDBus implements BluetoothStack, DeviceInquiryRunnable, SearchServicesRunnable {

    // This native lib contains the rfcomm and l2cap linux-specific
    // implementation for this bluez d-bus implementation.
    public static final String NATIVE_BLUECOVE_LIB_BLUEZ = "bluecovez";

    private final static String BLUEZ_DEVICEID_PREFIX = "hci";

    // Our reusable DBUS connection.
    private DBusConnection dbusConn = null;

    // Our reusable default host adapter.
    private Adapter adapter = null;

    // The current Manager.
    private Manager dbusManager = null;

    static final int BLUECOVE_DBUS_VERSION = BlueCoveImpl.nativeLibraryVersionExpected;

    /**
     * The parsed long value of the adapter's BT 00:00:... address.
     */
    private long localDeviceBTAddress = -1;

    private long sdpSesion;

    private int registeredServicesCount = 0;

    private Map<String, String> propertiesMap;

    private DiscoveryListener discoveryListener;

    private boolean deviceInquiryCanceled = false;

    private class DiscoveryData {

        public DeviceClass deviceClass;

        public String name;
    }

    BluetoothStackBlueZDBus() {
    }

    public String getStackID() {
        return BlueCoveImpl.STACK_BLUEZ_DBUS;
    }

    // --- Library initialization

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#isNativeCodeLoaded()
     */
    public native boolean isNativeCodeLoaded();

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#requireNativeLibraries()
     */
    public LibraryInformation[] requireNativeLibraries() {
        LibraryInformation unixSocketLib = new LibraryInformation("unix-java");
        unixSocketLib.stackClass = UnixSocket.class;
        return new LibraryInformation[] { new LibraryInformation(NATIVE_BLUECOVE_LIB_BLUEZ), unixSocketLib };
    }

    private native int getLibraryVersionNative();

    public int getLibraryVersion() throws BluetoothStateException {
        int version = getLibraryVersionNative();
        if (version != BLUECOVE_DBUS_VERSION) {
            DebugLog.fatal("BlueCove native library version mismatch " + version + " expected " + BLUECOVE_DBUS_VERSION);
            throw new BluetoothStateException("BlueCove native library version mismatch");
        }
        return version;
    }

    public int detectBluetoothStack() {
        return BlueCoveImpl.BLUECOVE_STACK_DETECT_BLUEZ;
    }

    /**
     * Returns a colon formatted BT address required by BlueZ. e.g.
     * 00:01:C2:51:D1:31
     * 
     * @param l
     *            The long address to be converted to a string.
     * @return Note: can be optimized - was playing around with the formats
     *         required by BlueZ.
     */
    private String toHexString(long l) {
        StringBuffer buf = new StringBuffer();
        String lo = Integer.toHexString((int) l);
        if (l > 0xffffffffl) {
            String hi = Integer.toHexString((int) (l >> 32));
            buf.append(hi);
        }
        buf.append(lo);
        StringBuffer result = new StringBuffer();
        int prependZeros = 12 - buf.length();
        for (int i = 0; i < prependZeros; ++i) {
            result.append("0");
        }
        result.append(buf.toString());
        StringBuffer hex = new StringBuffer();
        for (int i = 0; i < 12; i += 2) {
            hex.append(result.substring(i, i + 2));
            if (i < 10) {
                hex.append(":");
            }
        }
        return hex.toString();
    }

    public void initialize() throws BluetoothStateException {
        boolean intialized = false;
        try {
            try {
                dbusConn = DBusConnection.getConnection(DBusConnection.SYSTEM);
            } catch (DBusException e) {
                DebugLog.error("Failed to get the dbus connection", e);
                throw new BluetoothStateException(e.getMessage());
            }
            try {
                dbusManager = (Manager) dbusConn.getRemoteObject("org.bluez", "/org/bluez", Manager.class);
            } catch (DBusException e) {
                DebugLog.error("Failed to get bluez dbus manager", e);
                throw new BluetoothStateException(e.getMessage());
            }
            String adapterName = null;

            // If the user specifies a specific deviceID then we try to find it.
            String findID = BlueCoveImpl.getConfigProperty(BlueCoveConfigProperties.PROPERTY_LOCAL_DEVICE_ID);
            String deviceAddressStr = BlueCoveImpl.getConfigProperty(BlueCoveConfigProperties.PROPERTY_LOCAL_DEVICE_ADDRESS);
            if (findID != null) {
                if (findID.startsWith(BLUEZ_DEVICEID_PREFIX)) {
                    adapterName = dbusManager.FindAdapter(findID);
                    if (adapterName == null) {
                        throw new BluetoothStateException("Can't find '" + findID + "' adapter");
                    }
                } else {
                    int findNumber = Integer.parseInt(findID);
                    Object[] adapters = dbusManager.ListAdapters();
                    if (adapters == null) {
                        throw new BluetoothStateException("Can't find BlueZ adapters");
                    }
                    if ((findNumber < 0) || (findNumber >= adapters.length)) {
                        throw new BluetoothStateException("Can't find adapter #" + findID);
                    }
                    adapterName = String.valueOf(adapters[findNumber]);
                }
            } else if (deviceAddressStr != null) {
                adapterName = dbusManager.FindAdapter(toHexString(Long.parseLong(deviceAddressStr, 0x10)));
                if (adapterName == null) {
                    throw new BluetoothStateException("Can't find adapter with address '" + deviceAddressStr + "'");
                }
            } else {
                adapterName = dbusManager.DefaultAdapter();
                if (adapterName == null) {
                    throw new BluetoothStateException("Can't find default adapter");
                }
            }
            try {
                adapter = dbusConn.getRemoteObject("org.bluez", adapterName, Adapter.class);
            } catch (DBusException e) {
                throw new BluetoothStateException(e.getMessage());
            }
            if (adapter == null) {
                throw new BluetoothStateException("Can't connect to '" + adapterName + "' adapter");
            }
            localDeviceBTAddress = convertBTAddress(adapter.GetAddress());
            propertiesMap = new TreeMap<String, String>();
            // TODO
            propertiesMap.put(BluetoothConsts.PROPERTY_BLUETOOTH_SD_TRANS_MAX, "1");
            propertiesMap.put(BlueCoveLocalDeviceProperties.LOCAL_DEVICE_PROPERTY_DEVICE_ID, adapterName.substring(adapterName.indexOf(BLUEZ_DEVICEID_PREFIX)));
            intialized = true;
        } finally {
            if (!intialized) {
                if (dbusConn != null) {
                    dbusConn.disconnect();
                }
                dbusConn = null;
                adapter = null;
            }
        }
    }

    public void destroy() {
        DebugLog.debug("destroy()");
        if (sdpSesion != 0) {
            try {
                long s = sdpSesion;
                sdpSesion = 0;
                closeSDPSessionImpl(s, true);
            } catch (ServiceRegistrationException ignore) {
            }
        }
        if (dbusConn != null) {
            dbusConn.disconnect();
            dbusConn = null;
        }
    }

    @SuppressWarnings("unchecked")
    public native void enableNativeDebug(Class nativeDebugCallback, boolean on);

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.intel.bluetooth.BluetoothStack#isCurrentThreadInterruptedCallback()
     */
    public boolean isCurrentThreadInterruptedCallback() {
        // DebugLog.debug("isCurrentThreadInterruptedCallback()");
        return Thread.interrupted();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#getFeatureSet()
     */
    public int getFeatureSet() {
        return FEATURE_SERVICE_ATTRIBUTES | FEATURE_L2CAP;
    }

    // --- LocalDevice

    public String getLocalDeviceBluetoothAddress() throws BluetoothStateException {
        return RemoteDeviceHelper.getBluetoothAddress(convertBTAddress(adapter.GetAddress()));
    }

    public DeviceClass getLocalDeviceClass() {
        int record = 0;
        String major = adapter.GetMajorClass();

        if ("computer".equals(major)) {
            record |= BluetoothConsts.DeviceClassConsts.MAJOR_COMPUTER;
        } else {
            DebugLog.debug("Unknown MajorClass", major);
        }

        String minor = adapter.GetMinorClass();
        if (minor.equals("uncategorized")) {
            record |= BluetoothConsts.DeviceClassConsts.COMPUTER_MINOR_UNCLASSIFIED;
        } else if (minor.equals("desktop")) {
            record |= BluetoothConsts.DeviceClassConsts.COMPUTER_MINOR_DESKTOP;
        } else if (minor.equals("server")) {
            record |= BluetoothConsts.DeviceClassConsts.COMPUTER_MINOR_SERVER;
        } else if (minor.equals("laptop")) {
            record |= BluetoothConsts.DeviceClassConsts.COMPUTER_MINOR_LAPTOP;
        } else if (minor.equals("handheld")) {
            record |= BluetoothConsts.DeviceClassConsts.COMPUTER_MINOR_HANDHELD;
        } else if (minor.equals("palm")) {
            record |= BluetoothConsts.DeviceClassConsts.COMPUTER_MINOR_PALM;
        } else if (minor.equals("wearable")) {
            record |= BluetoothConsts.DeviceClassConsts.COMPUTER_MINOR_WEARABLE;
        } else {
            DebugLog.debug("Unknown MinorClass", minor);
            record |= BluetoothConsts.DeviceClassConsts.COMPUTER_MINOR_UNCLASSIFIED;
        }

        if (DiscoveryAgent.LIAC == getLocalDeviceDiscoverable()) {
            record |= BluetoothConsts.DeviceClassConsts.LIMITED_DISCOVERY_SERVICE;
        }

        String[] srvc = adapter.GetServiceClasses();
        if (srvc != null) {
            for (int s = 0; s < srvc.length; s++) {
                String serviceClass = srvc[s];
                if (serviceClass.equals("positioning")) {
                    record |= BluetoothConsts.DeviceClassConsts.POSITIONING_SERVICE;
                } else if (serviceClass.equals("networking")) {
                    record |= BluetoothConsts.DeviceClassConsts.NETWORKING_SERVICE;
                } else if (serviceClass.equals("rendering")) {
                    record |= BluetoothConsts.DeviceClassConsts.RENDERING_SERVICE;
                } else if (serviceClass.equals("capturing")) {
                    record |= BluetoothConsts.DeviceClassConsts.CAPTURING_SERVICE;
                } else if (serviceClass.equals("object transfer")) {
                    record |= BluetoothConsts.DeviceClassConsts.OBJECT_TRANSFER_SERVICE;
                } else if (serviceClass.equals("audio")) {
                    record |= BluetoothConsts.DeviceClassConsts.AUDIO_SERVICE;
                } else if (serviceClass.equals("telephony")) {
                    record |= BluetoothConsts.DeviceClassConsts.TELEPHONY_SERVICE;
                } else if (serviceClass.equals("information")) {
                    record |= BluetoothConsts.DeviceClassConsts.INFORMATION_SERVICE;
                } else {
                    DebugLog.debug("Unknown ServiceClasses", serviceClass);
                }
            }
        }

        return new DeviceClass(record);
    }

    /**
     * Retrieves the name of the local device.
     * 
     * @see javax.bluetooth.LocalDevice#getFriendlyName()
     */
    public String getLocalDeviceName() {
        try {
            return adapter.GetName();
        } catch (NotReady e) {
            return null;
        } catch (Failed e) {
            return null;
        }
    }

    public boolean isLocalDevicePowerOn() {
        return !"off".equals(adapter.GetMode());
    }

    public String getLocalDeviceProperty(String property) {
        if (BlueCoveLocalDeviceProperties.LOCAL_DEVICE_DEVICES_LIST.equals(property)) {
            Object[] adapters = dbusManager.ListAdapters();
            StringBuffer b = new StringBuffer();
            if (adapters != null) {
                for (int i = 0; i < adapters.length; i++) {
                    if (i != 0) {
                        b.append(',');
                    }
                    String adapterId = String.valueOf(adapters[i]);
                    final String bluezPath = "/org/bluez/";
                    if (adapterId.startsWith(bluezPath)) {
                        adapterId = adapterId.substring(bluezPath.length());
                    }
                    //b.append(BLUEZ_DEVICEID_PREFIX);
                    b.append(adapterId);
                }
            }
            return b.toString();
        } else if (BlueCoveLocalDeviceProperties.LOCAL_DEVICE_RADIO_VERSION.equals(property)) {
            return adapter.GetVersion() + "; HCI " + adapter.GetRevision();
        } else if (BlueCoveLocalDeviceProperties.LOCAL_DEVICE_RADIO_MANUFACTURER.equals(property)) {
            return adapter.GetManufacturer();
        } else {
            return propertiesMap.get(property);
        }
    }

    public int getLocalDeviceDiscoverable() {
        if (adapter.IsDiscoverable()) {
            UInt32 timeout = adapter.GetDiscoverableTimeout();
            if ((timeout == null) || (timeout.intValue() == 0)) {
                return DiscoveryAgent.GIAC;
            } else {
                return DiscoveryAgent.LIAC;
            }
        } else {
            return DiscoveryAgent.NOT_DISCOVERABLE;
        }
    }

    public boolean setLocalDeviceDiscoverable(int mode) throws BluetoothStateException {
        if (getLocalDeviceDiscoverable() == mode) {
            return true;
        }
        String modeStr;
        switch (mode) {
        case DiscoveryAgent.NOT_DISCOVERABLE:
            modeStr = "connectable";
            break;
        case DiscoveryAgent.GIAC:
            modeStr = "discoverable";
            break;
        case DiscoveryAgent.LIAC:
            modeStr = "limited";
            break;
        default:
            throw new IllegalArgumentException("Invalid discoverable mode");
        }
        try {
            adapter.SetMode(modeStr);
            return true;
        } catch (Failed e) {
            throw new BluetoothStateException(e.getMessage());
        } catch (NoSuchAdapter e) {
            throw new BluetoothStateException(e.getMessage());
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#setLocalDeviceServiceClasses(int)
     */
    public void setLocalDeviceServiceClasses(int classOfDevice) {
        DebugLog.debug("setLocalDeviceServiceClasses()");
        throw new NotSupportedRuntimeException(getStackID());
    }

    public boolean authenticateRemoteDevice(long address) throws IOException {
        try {
            adapter.CreateBonding(toHexString(address));
            return true;
        } catch (Throwable e) {
            DebugLog.error("Error creating bonding", e);
            return false;
        }
    }

    public boolean authenticateRemoteDevice(long address, String passkey) throws IOException {
        if (passkey == null) {
            try {
                adapter.CreateBonding(toHexString(address));
                return true;
            } catch (Throwable e) {
                throw new IOException(e);
            }   
        } else {
            return false;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.intel.bluetooth.BluetoothStack#removeAuthenticationWithRemoteDevice
     * (long)
     */
    public void removeAuthenticationWithRemoteDevice(long address) throws IOException {
        try {
            adapter.RemoveBonding(toHexString(address));
        } catch (Throwable e) {
            throw new IOException(e);
        }
    }

    // --- Device Inquiry

    public boolean startInquiry(int accessCode, DiscoveryListener listener) throws BluetoothStateException {
        DebugLog.debug("startInquiry()");
        if (discoveryListener != null) {
            throw new BluetoothStateException("Another inquiry already running");
        }
        discoveryListener = listener;
        deviceInquiryCanceled = false;
        return DeviceInquiryThread.startInquiry(this, this, accessCode, listener);
    }

    private int runDeviceInquiryImpl(DeviceInquiryThread startedNotify, int accessCode, int inquiryLength, int maxResponses, final DiscoveryListener listener)
            throws BluetoothStateException {

        try {
            final Object discoveryCompletedEvent = new Object();

            DBusSigHandler<Adapter.DiscoveryCompleted> discoveryCompleted = new DBusSigHandler<Adapter.DiscoveryCompleted>() {
                public void handle(Adapter.DiscoveryCompleted s) {
                    DebugLog.debug("discoveryCompleted.handle()");
                    synchronized (discoveryCompletedEvent) {
                        discoveryCompletedEvent.notifyAll();
                    }
                }
            };
            dbusConn.addSigHandler(Adapter.DiscoveryCompleted.class, discoveryCompleted);

            DBusSigHandler<Adapter.DiscoveryStarted> discoveryStarted = new DBusSigHandler<Adapter.DiscoveryStarted>() {
                public void handle(Adapter.DiscoveryStarted s) {
                    DebugLog.debug("device discovery procedure has been started.");
                }
            };
            dbusConn.addSigHandler(Adapter.DiscoveryStarted.class, discoveryStarted);

            // Different signal handlers get different device attributes
            // so we cache the data until device discovery is finished
            // and then create the RemoteDevice objects.
            final Map<Long, DiscoveryData> address2DiscoveryData = new HashMap<Long, DiscoveryData>();

            final Map<String, Adapter.RemoteDeviceFound> devicesDiscovered = new HashMap<String, Adapter.RemoteDeviceFound>();
            DBusSigHandler<Adapter.RemoteDeviceFound> remoteDeviceFound = new DBusSigHandler<Adapter.RemoteDeviceFound>() {
                public void handle(Adapter.RemoteDeviceFound s) {
                    if (devicesDiscovered.containsKey(s.address)) {
                        return;
                    }
                    // dbus doesn't give us the remote device name so we
                    // can't create the RemoteDevice here as we can never set
                    // the device name later during remoteNameUpdated.
                    DebugLog.debug("device found " + s.address + " , name:" + s.getName() + ", destination:" + s.getDestination() + ", interface:"
                            + s.getInterface() + ", path:" + s.getPath() + ", sig:" + s.getSig() + ", source:" + s.getSource() + ", device class:"
                            + s.deviceClass.intValue());
                    devicesDiscovered.put(s.address, s);
                    DeviceClass deviceClass = new DeviceClass(s.deviceClass.intValue());
                    long longAddress = convertBTAddress(s.address);
                    DiscoveryData discoveryData = address2DiscoveryData.get(longAddress);
                    if (discoveryData == null) {
                        discoveryData = new DiscoveryData();
                        address2DiscoveryData.put(longAddress, discoveryData);
                    }
                    discoveryData.deviceClass = deviceClass;
                }
            };
            dbusConn.addSigHandler(Adapter.RemoteDeviceFound.class, remoteDeviceFound);

            DBusSigHandler<Adapter.RemoteNameUpdated> remoteNameUpdated = new DBusSigHandler<Adapter.RemoteNameUpdated>() {
                public void handle(Adapter.RemoteNameUpdated s) {
                    DebugLog.debug("deviceNameUpdated() " + s.address + " " + s.name);
                    long longAddress = convertBTAddress(s.address);
                    DiscoveryData discoveryData = address2DiscoveryData.get(longAddress);
                    if (discoveryData == null) {
                        discoveryData = new DiscoveryData();
                        address2DiscoveryData.put(longAddress, discoveryData);
                    }
                    discoveryData.name = s.name;
                }
            };
            dbusConn.addSigHandler(Adapter.RemoteNameUpdated.class, remoteNameUpdated);

            synchronized (discoveryCompletedEvent) {
                adapter.DiscoverDevices();
                startedNotify.deviceInquiryStartedCallback();
                DebugLog.debug("wait for device inquiry to complete...");
                try {
                    discoveryCompletedEvent.wait();
                    DebugLog.debug(devicesDiscovered.size() + " device(s) found");
                    if (deviceInquiryCanceled) {
                        return DiscoveryListener.INQUIRY_TERMINATED;
                    }

                    for (Long address : address2DiscoveryData.keySet()) {
                        DiscoveryData discoveryData = address2DiscoveryData.get(address);
                        boolean paired = adapter.HasBonding(toHexString(address.longValue()));
                        RemoteDevice remoteDevice = RemoteDeviceHelper.createRemoteDevice(BluetoothStackBlueZDBus.this, address, discoveryData.name, paired);
                        listener.deviceDiscovered(remoteDevice, discoveryData.deviceClass);
                    }
                    return DiscoveryListener.INQUIRY_COMPLETED;
                } catch (InterruptedException e) {
                    DebugLog.error("Discovery interrupted.");
                    return DiscoveryListener.INQUIRY_TERMINATED;
                } catch (Exception e) {
                    DebugLog.error("Discovery process failed", e);
                    throw new BluetoothStateException("Device Inquiry failed:" + e.getMessage());
                } finally {
                    dbusConn.removeSigHandler(Adapter.RemoteNameUpdated.class, remoteNameUpdated);
                    dbusConn.removeSigHandler(Adapter.RemoteDeviceFound.class, remoteDeviceFound);
                    dbusConn.removeSigHandler(Adapter.DiscoveryCompleted.class, discoveryCompleted);
                }
            }
        } catch (DBusException e) {
            DebugLog.error("Discovery dbus problem", e);
            throw new BluetoothStateException("Device Inquiry failed:" + e.getMessage());
        }
    }

    public int runDeviceInquiry(DeviceInquiryThread startedNotify, int accessCode, DiscoveryListener listener) throws BluetoothStateException {
        DebugLog.debug("runDeviceInquiry()");
        try {
            int discType = runDeviceInquiryImpl(startedNotify, accessCode, 8, 20, listener);
            if (deviceInquiryCanceled) {
                return DiscoveryListener.INQUIRY_TERMINATED;
            }
            return discType;
        } finally {
            discoveryListener = null;
        }
    }

    public void deviceDiscoveredCallback(DiscoveryListener listener, long deviceAddr, int deviceClass, String deviceName, boolean paired) {
        // Not used here since there are no native callbacks
    }

    public boolean cancelInquiry(DiscoveryListener listener) {
        DebugLog.debug("cancelInquiry()");
        if (discoveryListener != null && discoveryListener == listener) {
            deviceInquiryCanceled = true;
            adapter.CancelDiscovery();
            return true; // TODO: how could the be true or false?
        } else {
            return false;
        }
    }

    /**
     * Contact the remote device
     */
    public String getRemoteDeviceFriendlyName(final long anAddress) throws IOException {
        // return adapter.GetRemoteName(toHexString(anAddress));
        // For JSR-82 GetRemoteName can't be since it use cash.
        
        DebugLog.debug("getRemoteDeviceFriendlyName()");
        try {
            final Object discoveryCompletedEvent = new Object();
            final Vector<String> namesFound = new Vector<String>();

            DBusSigHandler<Adapter.DiscoveryCompleted> discoveryCompleted = new DBusSigHandler<Adapter.DiscoveryCompleted>() {
                public void handle(Adapter.DiscoveryCompleted s) {
                    DebugLog.debug("discoveryCompleted.handle()");
                    synchronized (discoveryCompletedEvent) {
                        discoveryCompletedEvent.notifyAll();
                    }
                }
            };
            dbusConn.addSigHandler(Adapter.DiscoveryCompleted.class, discoveryCompleted);

            DBusSigHandler<Adapter.RemoteNameUpdated> remoteNameUpdated = new DBusSigHandler<Adapter.RemoteNameUpdated>() {
                public void handle(Adapter.RemoteNameUpdated s) {
                    if (convertBTAddress(s.address) == anAddress) {
                        if (s.name != null) {
                            namesFound.add(s.name);
                            synchronized (discoveryCompletedEvent) {
                                discoveryCompletedEvent.notifyAll();
                            }
                        } else {
                            DebugLog.debug("device name is null");    
                        }
                    } else {
                        DebugLog.debug("ignore device name " + s.address + " " + s.name);
                    }
                }
            };
            dbusConn.addSigHandler(Adapter.RemoteNameUpdated.class, remoteNameUpdated);

            synchronized (discoveryCompletedEvent) {
                adapter.DiscoverDevices();
                DebugLog.debug("wait for device inquiry to complete...");
                try {
                    discoveryCompletedEvent.wait();
                    DebugLog.debug(namesFound.size() + " device name(s) found");
                    if (namesFound.size() == 0) {
                        throw new IOException("Can't retrive device name");
                    }
                    // return the last name found
                    return namesFound.get(namesFound.size() - 1);
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                } finally {
                    dbusConn.removeSigHandler(Adapter.RemoteNameUpdated.class, remoteNameUpdated);
                    dbusConn.removeSigHandler(Adapter.DiscoveryCompleted.class, discoveryCompleted);
                }
            }
        } catch (DBusException e) {
            DebugLog.error("Discovery dbus problem", e);
            throw new IOException("Device Inquiry failed:" + e.getMessage());
        }
    }

    public RemoteDevice[] retrieveDevices(int option) {
        if (DiscoveryAgent.PREKNOWN == option) {
            final Vector<RemoteDevice> devices = new Vector<RemoteDevice>();
            String[] bonded = adapter.ListBondings();
            if (bonded != null) {
                for(int i= 0; i < bonded.length; i ++) {
                    devices.add(RemoteDeviceHelper.createRemoteDevice(this, convertBTAddress(bonded[i]), null, true));
                }
            }
            String[] trusted = adapter.ListTrusts();
            if (trusted != null) {
                for(int i= 0; i < trusted.length; i ++) {
                    devices.add(RemoteDeviceHelper.createRemoteDevice(this, convertBTAddress(trusted[i]), null, false));
                }
            }
            return RemoteDeviceHelper.remoteDeviceListToArray(devices);
        } else {
            return null;
        }
    }

    public Boolean isRemoteDeviceTrusted(long address) {
        return Boolean.valueOf(adapter.HasBonding(toHexString(address)));
    }

    public Boolean isRemoteDeviceAuthenticated(long address) {
        return Boolean.valueOf(adapter.IsConnected(toHexString(address)) && adapter.HasBonding(toHexString(address)));
    }

    // --- Service search

    /**
     * Starts searching for services.
     * 
     * @return transId
     */
    public int searchServices(int[] attrSet, UUID[] uuidSet, RemoteDevice device, DiscoveryListener listener) throws BluetoothStateException {
        try {
            DebugLog.debug("searchServices() device:" + device.getFriendlyName(false));
            return SearchServicesThread.startSearchServices(this, this, attrSet, uuidSet, device, listener);
        } catch (Exception ex) {
            DebugLog.debug("searchServices() failed", ex);
            throw new BluetoothStateException("searchServices() failed: " + ex.getMessage());
        }
    }

    /**
     * Finds services. Implements interface: SearchServicesRunnable
     * 
     * @param sst
     * @param localDeviceBTAddress
     * @param uuidValues
     * @param remoteDeviceAddress
     * @return
     * @throws SearchServicesException
     */
    private int getRemoteServices(SearchServicesThread sst, UUID[] uuidSet, long remoteDeviceAddress) throws SearchServicesException {
        DebugLog.debug0x("getRemoteServices", remoteDeviceAddress);
        // 1. uuidValues need to be converted somehow to a match String.
        // http://wiki.bluez.org/wiki/HOWTO/DiscoveringServices states:
        // "Currently, the BlueZ D-Bus API supports only a single pattern."
        // So, instead we match everything and do our own matching further
        // down.
        String match = "";
        UInt32[] serviceHandles = null;
        String hexAddress = toHexString(remoteDeviceAddress);
        try {
            serviceHandles = adapter.GetRemoteServiceHandles(hexAddress, match);
        } catch (Throwable t) {
            DebugLog.debug("GetRemoteServiceHandles() failed:", t);
            return DiscoveryListener.SERVICE_SEARCH_ERROR;
        }
        DebugLog.debug("GetRemoteServiceHandles() done.");
        if ((serviceHandles == null) || (serviceHandles.length == 0)) {
            return DiscoveryListener.SERVICE_SEARCH_NO_RECORDS;
        }
        DebugLog.debug("Found serviceHandles:" + serviceHandles.length);
        RemoteDevice remoteDevice = RemoteDeviceHelper.getCashedDevice(this, remoteDeviceAddress);
        nextRecord: for (int i = 0; i < serviceHandles.length; ++i) {
            UInt32 handle = serviceHandles[i];
            try {
                byte[] serviceRecordBytes = adapter.GetRemoteServiceRecord(hexAddress, handle);
                ServiceRecordImpl sr = new ServiceRecordImpl(this, remoteDevice, handle.intValue());
                sr.loadByteArray(serviceRecordBytes);
                for (int u = 0; u < uuidSet.length; u++) {
                    if (!((sr.hasServiceClassUUID(uuidSet[u])) || (sr.hasProtocolClassUUID(uuidSet[u])))) {
                        DebugLog.debug("ignoring service", sr);
                        continue nextRecord; 
                    }
                }
                DebugLog.debug("found service", i);
                sst.addServicesRecords(sr);
            } catch (IOException e) {
                DebugLog.debug("Failed to load serviceRecordBytes", e);
                // TODO: Is there any logical reason to parse other records?
                // throw new SearchServicesException("runSearchServicesImpl()
                // failed to parse the service record.");
            }
        }
        return DiscoveryListener.SERVICE_SEARCH_COMPLETED;
    }

    public int runSearchServices(SearchServicesThread sst, int[] attrSet, UUID[] uuidSet, RemoteDevice device, DiscoveryListener listener)
            throws BluetoothStateException {
        DebugLog.debug("runSearchServices()");
        sst.searchServicesStartedCallback();
        try {
            int respCode = getRemoteServices(sst, uuidSet, RemoteDeviceHelper.getAddress(device));
            if ((respCode != DiscoveryListener.SERVICE_SEARCH_ERROR) && (sst.isTerminated())) {
                return DiscoveryListener.SERVICE_SEARCH_TERMINATED;
            } else if (respCode == DiscoveryListener.SERVICE_SEARCH_COMPLETED) {
                Vector<ServiceRecord> records = sst.getServicesRecords();
                if (records.size() != 0) {
                    DebugLog.debug("SearchServices finished", sst.getTransID());
                    ServiceRecord[] servRecordArray = (ServiceRecord[]) Utils.vector2toArray(records, new ServiceRecord[records.size()]);
                    listener.servicesDiscovered(sst.getTransID(), servRecordArray);
                    return DiscoveryListener.SERVICE_SEARCH_COMPLETED;
                } else {
                    return DiscoveryListener.SERVICE_SEARCH_NO_RECORDS;
                }
            } else {
                return respCode;
            }
        } catch (SearchServicesDeviceNotReachableException e) {
            return DiscoveryListener.SERVICE_SEARCH_DEVICE_NOT_REACHABLE;
        } catch (SearchServicesTerminatedException e) {
            return DiscoveryListener.SERVICE_SEARCH_TERMINATED;
        } catch (SearchServicesException e) {
            return DiscoveryListener.SERVICE_SEARCH_ERROR;
        }
    }

    public boolean cancelServiceSearch(int transID) {
        DebugLog.debug("cancelServiceSearch()");
        SearchServicesThread sst = SearchServicesThread.getServiceSearchThread(transID);
        if (sst != null) {
            return sst.setTerminated();
        } else {
            return false;
        }
    }

    // private native boolean populateServiceRecordAttributeValuesImpl(long
    // localDeviceBTAddress,
    // long remoteDeviceAddress, long sdpSession, long handle, int[] attrIDs,
    // ServiceRecordImpl serviceRecord);
    private boolean populateServiceRecordAttributeValuesImpl(long remoteDeviceAddress, long sdpSession, long handle, int[] attrIDs,
            ServiceRecordImpl serviceRecord) {
        throw new UnsupportedOperationException("populateServiceRecordAttributeValuesImpl() Not supported yet.");
    }

    private long convertBTAddress(String anAddress) {
        long btAddress = Long.parseLong(anAddress.replaceAll(":", ""), 16);
        return btAddress;
    }

    public boolean populateServicesRecordAttributeValues(ServiceRecordImpl serviceRecord, int[] attrIDs) throws IOException {
        DebugLog.debug("populateServicesRecordAttributeValues()");
        long remoteDeviceAddress = RemoteDeviceHelper.getAddress(serviceRecord.getHostDevice());
        return populateServiceRecordAttributeValuesImpl(remoteDeviceAddress, 0, serviceRecord.getHandle(), attrIDs, serviceRecord);
    }

    // --- SDP Server

    // private native long openSDPSessionImpl() throws
    // ServiceRegistrationException;
    private long openSDPSessionImpl() throws ServiceRegistrationException {
        throw new ServiceRegistrationException("openSDPSessionImpl() Not supported yet.");
    }

    private synchronized long getSDPSession() throws ServiceRegistrationException {
        if (this.sdpSesion == 0) {
            sdpSesion = openSDPSessionImpl();
            DebugLog.debug("created SDPSession", sdpSesion);
        }
        return sdpSesion;
    }

    // private native void closeSDPSessionImpl(long sdpSesion, boolean quietly)
    // throws ServiceRegistrationException;
    private void closeSDPSessionImpl(long sdpSesion, boolean quietly) throws ServiceRegistrationException {

        throw new ServiceRegistrationException("closeSDPSessionImpl() Not supported yet.");
    }

    // private native long registerSDPServiceImpl(long sdpSesion, long
    // localDeviceBTAddress, byte[] record)
    // throws ServiceRegistrationException;
    private long registerSDPServiceImpl(long sdpSesion, byte[] record) throws ServiceRegistrationException {

        throw new ServiceRegistrationException("registerSDPServiceImpl() Not supported yet.");
    }

    // private native void updateSDPServiceImpl(long sdpSesion, long
    // localDeviceBTAddress, long handle, byte[] record)
    // throws ServiceRegistrationException;
    private void updateSDPServiceImpl(long sdpSesion, long handle, byte[] record) throws ServiceRegistrationException {

        throw new ServiceRegistrationException("updateSDPServiceImpl() Not supported yet.");
    }

    // private native void unregisterSDPServiceImpl(long sdpSesion, long
    // localDeviceBTAddress, long handle, byte[] record)
    // throws ServiceRegistrationException;
    private void unregisterSDPServiceImpl(long sdpSesion, long handle, byte[] record) throws ServiceRegistrationException {

        throw new ServiceRegistrationException("unregisterSDPServiceImpl() Not supported yet.");
    }

    private byte[] getSDPBinary(ServiceRecordImpl serviceRecord) throws ServiceRegistrationException {
        byte[] blob;
        try {
            blob = serviceRecord.toByteArray();
        } catch (IOException e) {
            throw new ServiceRegistrationException(e.toString());
        }
        return blob;
    }

    private synchronized void registerSDPRecord(ServiceRecordImpl serviceRecord) throws ServiceRegistrationException {
        long handle = registerSDPServiceImpl(getSDPSession(), getSDPBinary(serviceRecord));
        serviceRecord.setHandle(handle);
        serviceRecord.populateAttributeValue(BluetoothConsts.ServiceRecordHandle, new DataElement(DataElement.U_INT_4, handle));
        registeredServicesCount++;
    }

    private void updateSDPRecord(ServiceRecordImpl serviceRecord) throws ServiceRegistrationException {
        updateSDPServiceImpl(getSDPSession(), serviceRecord.getHandle(), getSDPBinary(serviceRecord));
    }

    private synchronized void unregisterSDPRecord(ServiceRecordImpl serviceRecord) throws ServiceRegistrationException {
        try {
            unregisterSDPServiceImpl(getSDPSession(), serviceRecord.getHandle(), getSDPBinary(serviceRecord));
        } finally {
            registeredServicesCount--;
            if (registeredServicesCount <= 0) {
                registeredServicesCount = 0;
                DebugLog.debug("closeSDPSession", sdpSesion);
                long s = sdpSesion;
                sdpSesion = 0;
                closeSDPSessionImpl(s, false);
            }
        }
    }

    // --- Client RFCOMM connections

    private native long connectionRfOpenClientConnectionImpl(long localDeviceBTAddress, long address, int channel, boolean authenticate, boolean encrypt,
            int timeout) throws IOException;

    public long connectionRfOpenClientConnection(BluetoothConnectionParams params) throws IOException {
        DebugLog.debug("connectionRfOpenClientConnection()");
        return connectionRfOpenClientConnectionImpl(this.localDeviceBTAddress, params.address, params.channel, params.authenticate, params.encrypt,
                params.timeout);
    }

    public native void connectionRfCloseClientConnection(long handle) throws IOException;

    public native int rfGetSecurityOptImpl(long handle) throws IOException;

    public int rfGetSecurityOpt(long handle, int expected) throws IOException {
        return rfGetSecurityOptImpl(handle);
    }

    public boolean rfEncrypt(long address, long handle, boolean on) throws IOException {
        // TODO
        return false;
    }

    private native long rfServerOpenImpl(long localDeviceBTAddress, boolean authorize, boolean authenticate, boolean encrypt, boolean master, boolean timeouts,
            int backlog) throws IOException;

    private native int rfServerGetChannelIDImpl(long handle) throws IOException;

    public long rfServerOpen(BluetoothConnectionNotifierParams params, ServiceRecordImpl serviceRecord) throws IOException {
        final int listen_backlog = 1;
        long socket = rfServerOpenImpl(this.localDeviceBTAddress, params.authorize, params.authenticate, params.encrypt, params.master, params.timeouts,
                listen_backlog);
        boolean success = false;
        try {
            int channel = rfServerGetChannelIDImpl(socket);
            serviceRecord.populateRFCOMMAttributes(0, channel, params.uuid, params.name, params.obex);
            registerSDPRecord(serviceRecord);
            success = true;
            return socket;
        } finally {
            if (!success) {
                rfServerCloseImpl(socket, true);
            }
        }
    }

    private native void rfServerCloseImpl(long handle, boolean quietly) throws IOException;

    public void rfServerClose(long handle, ServiceRecordImpl serviceRecord) throws IOException {
        try {
            unregisterSDPRecord(serviceRecord);
        } finally {
            rfServerCloseImpl(handle, false);
        }
    }

    public void rfServerUpdateServiceRecord(long handle, ServiceRecordImpl serviceRecord, boolean acceptAndOpen) throws ServiceRegistrationException {
        updateSDPRecord(serviceRecord);
    }

    // public native long rfServerAcceptAndOpenRfServerConnection(long handle)
    // throws IOException;
    public long rfServerAcceptAndOpenRfServerConnection(long handle) throws IOException {
        throw new IOException("rfServerAcceptAndOpenRfServerConnection() Not supported yet.");
    }

    public void connectionRfCloseServerConnection(long clientHandle) throws IOException {
        connectionRfCloseClientConnection(clientHandle);
    }

    // --- Shared Client and Server RFCOMM connections

    public int connectionRfRead(long handle) throws IOException {
        byte[] data = new byte[1];
        int size = connectionRfRead(handle, data, 0, 1);
        if (size == -1) {
            return -1;
        }
        return 0xFF & data[0];
    }

    public native int connectionRfRead(long handle, byte[] b, int off, int len) throws IOException;

    public native int connectionRfReadAvailable(long handle) throws IOException;

    public native void connectionRfWrite(long handle, int b) throws IOException;

    public native void connectionRfWrite(long handle, byte[] b, int off, int len) throws IOException;

    public native void connectionRfFlush(long handle) throws IOException;

    public native long getConnectionRfRemoteAddress(long handle) throws IOException;

    // --- Client and Server L2CAP connections

    private native long l2OpenClientConnectionImpl(long localDeviceBTAddress, long address, int channel, boolean authenticate, boolean encrypt, int receiveMTU,
            int transmitMTU, int timeout) throws IOException;

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.intel.bluetooth.BluetoothStack#l2OpenClientConnection(com.intel.bluetooth
     * .BluetoothConnectionParams, int, int)
     */
    public long l2OpenClientConnection(BluetoothConnectionParams params, int receiveMTU, int transmitMTU) throws IOException {

        return l2OpenClientConnectionImpl(localDeviceBTAddress, params.address, params.channel, params.authenticate, params.encrypt, receiveMTU, transmitMTU,
                params.timeout);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#l2CloseClientConnection(long)
     */
    public native void l2CloseClientConnection(long handle) throws IOException;

    private native long l2ServerOpenImpl(long localDeviceBTAddress, boolean authorize, boolean authenticate, boolean encrypt, boolean master, boolean timeouts,
            int backlog, int receiveMTU, int transmitMTU, int assignPsm) throws IOException;

    public native int l2ServerGetPSMImpl(long handle) throws IOException;

    /*
     * (non-Javadoc)
     * 
     * @seecom.intel.bluetooth.BluetoothStack#l2ServerOpen(com.intel.bluetooth.
     * BluetoothConnectionNotifierParams, int, int,
     * com.intel.bluetooth.ServiceRecordImpl)
     */
    public long l2ServerOpen(BluetoothConnectionNotifierParams params, int receiveMTU, int transmitMTU, ServiceRecordImpl serviceRecord) throws IOException {
        final int listen_backlog = 1;
        long socket = l2ServerOpenImpl(this.localDeviceBTAddress, params.authorize, params.authenticate, params.encrypt, params.master, params.timeouts,
                listen_backlog, receiveMTU, transmitMTU, params.bluecove_ext_psm);
        boolean success = false;
        try {
            int channel = l2ServerGetPSMImpl(socket);
            serviceRecord.populateL2CAPAttributes(0, channel, params.uuid, params.name);
            registerSDPRecord(serviceRecord);
            success = true;
            return socket;
        } finally {
            if (!success) {
                l2ServerCloseImpl(socket, true);
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#l2ServerUpdateServiceRecord(long,
     * com.intel.bluetooth.ServiceRecordImpl, boolean)
     */
    public void l2ServerUpdateServiceRecord(long handle, ServiceRecordImpl serviceRecord, boolean acceptAndOpen) throws ServiceRegistrationException {
        updateSDPRecord(serviceRecord);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.intel.bluetooth.BluetoothStack#l2ServerAcceptAndOpenServerConnection
     * (long)
     */
    public native long l2ServerAcceptAndOpenServerConnection(long handle) throws IOException;

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#l2CloseServerConnection(long)
     */
    public void l2CloseServerConnection(long handle) throws IOException {
        l2CloseClientConnection(handle);
    }

    private native void l2ServerCloseImpl(long handle, boolean quietly) throws IOException;

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#l2ServerClose(long,
     * com.intel.bluetooth.ServiceRecordImpl)
     */
    public void l2ServerClose(long handle, ServiceRecordImpl serviceRecord) throws IOException {
        try {
            unregisterSDPRecord(serviceRecord);
        } finally {
            l2ServerCloseImpl(handle, false);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#l2Ready(long)
     */
    public native boolean l2Ready(long handle) throws IOException;

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#l2receive(long, byte[])
     */
    public native int l2Receive(long handle, byte[] inBuf) throws IOException;

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#l2send(long, byte[])
     */
    public native void l2Send(long handle, byte[] data) throws IOException;

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#l2GetReceiveMTU(long)
     */
    public native int l2GetReceiveMTU(long handle) throws IOException;

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#l2GetTransmitMTU(long)
     */
    public native int l2GetTransmitMTU(long handle) throws IOException;

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#l2RemoteAddress(long)
     */
    public native long l2RemoteAddress(long handle) throws IOException;

    /*
     * (non-Javadoc)
     * 
     * @see com.intel.bluetooth.BluetoothStack#l2GetSecurityOpt(long, int)
     */
    public native int l2GetSecurityOpt(long handle, int expected) throws IOException;

    public boolean l2Encrypt(long address, long handle, boolean on) throws IOException {
        // TODO
        return false;
    }

}