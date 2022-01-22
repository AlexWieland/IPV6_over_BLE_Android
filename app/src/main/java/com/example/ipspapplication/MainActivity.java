package com.example.ipspapplication;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import android.os.StrictMode;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private final String CONNECT_TAG = "CONN-DeviceTryToConnect";
    private BluetoothAdapter mBluetoothAdapter = null;
    private BroadcastReceiver mReceiver = null;
    private ArrayList<String> mDeviceNameList;
    private ArrayList<BluetoothDevice> mDeviceList;
    private ArrayAdapter<String> mArrayAdapter = null;
    private ListView mDeviceListView;
    private BluetoothLeScanner mBLEScanner;

    private Button btnConnect;
    private Button btnRefresh;
    private boolean refreshing = false;
    private BluetoothDevice mSelectedDevice;

    private byte[] linkLocalRouterAddress;
    private byte[] linkLocalPeripheralAddress;
    private byte[] selectedDeviceAddress;
    private byte[] selfDeviceAddress;

    public ArrayList<ConnectThread> bluetoothConnections;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd_MM_yyyy_hh_mm_ss", Locale.getDefault());
            String logDate = dateFormat.format(new Date());
            // Applies the date and time to the name of the trace log.
            Debug.startMethodTracing("sample-" + logDate);

            bluetoothConnections = new ArrayList<ConnectThread>();

            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);

            //if readout own device address it can get set manually here (linkLocalRouterAddress is the own address)
            linkLocalRouterAddress = new byte[16];
            linkLocalRouterAddress[0] = (byte) 0xfe;
            linkLocalRouterAddress[1] = (byte) 0x80;
            linkLocalRouterAddress[2] = (byte) 0x00;
            linkLocalRouterAddress[3] = (byte) 0x00;
            linkLocalRouterAddress[4] = (byte) 0x00;
            linkLocalRouterAddress[5] = (byte) 0x00;
            linkLocalRouterAddress[6] = (byte) 0x00;
            linkLocalRouterAddress[7] = (byte) 0x00;
            linkLocalRouterAddress[8] = (byte) 0x78;
            linkLocalRouterAddress[9] = (byte) 0xf8;
            linkLocalRouterAddress[10] = (byte) 0x82;
            linkLocalRouterAddress[11] = (byte) 0xff;
            linkLocalRouterAddress[12] = (byte) 0xfe;
            linkLocalRouterAddress[13] = (byte) 0x52;
            linkLocalRouterAddress[14] = (byte) 0x5f;
            linkLocalRouterAddress[15] = (byte) 0x3f;

            //FE80:0000:0000:0000:FE3D:4CFF:FEC1:6E0C
            linkLocalPeripheralAddress = new byte[16];
            linkLocalPeripheralAddress[0] = (byte) 0xfe;
            linkLocalPeripheralAddress[1] = (byte) 0x80;
            linkLocalPeripheralAddress[2] = (byte) 0x00;
            linkLocalPeripheralAddress[3] = (byte) 0x00;
            linkLocalPeripheralAddress[4] = (byte) 0x00;
            linkLocalPeripheralAddress[5] = (byte) 0x00;
            linkLocalPeripheralAddress[6] = (byte) 0x00;
            linkLocalPeripheralAddress[7] = (byte) 0x00;

            //its possible to set manually address of the peripheral device
            linkLocalPeripheralAddress[8] = (byte) 0xfe;
            linkLocalPeripheralAddress[9] = (byte) 0x3d;
            linkLocalPeripheralAddress[10] = (byte) 0x4c;
            linkLocalPeripheralAddress[11] = (byte) 0xff;
            linkLocalPeripheralAddress[12] = (byte) 0xfe;
            linkLocalPeripheralAddress[13] = (byte) 0xc1;
            linkLocalPeripheralAddress[14] = (byte) 0x6e;
            linkLocalPeripheralAddress[15] = (byte) 0x0c;

            //--------------------

            this.mDeviceList = new ArrayList<>();
            this.mDeviceNameList = new ArrayList<>();
            this.mDeviceListView = (ListView) findViewById(R.id.listView1);
            this.mDeviceListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    Log.d(CONNECT_TAG, "+++ position of item: " + position);
                    mSelectedDevice = mDeviceList.get(position);
                }
            });

            this.mArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, this.mDeviceNameList);
            this.mDeviceListView.setAdapter(this.mArrayAdapter);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("This app needs location access");
                    builder.setMessage("Please grant location access so this app can detect beacons");
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
                        }
                    });
                    builder.show();
                }
            }

            btnConnect = (Button) findViewById(R.id.buttonConnect);
            btnConnect.setOnClickListener(this);
            btnRefresh = (Button) findViewById(R.id.buttonRefresh);
            btnRefresh.setOnClickListener(this);

            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            mBluetoothAdapter.startDiscovery();
            this.mBLEScanner = mBluetoothAdapter.getBluetoothLeScanner();

            mReceiver = new BroadcastReceiver() {


                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();

                    //Finding devices
                    if (BluetoothDevice.ACTION_FOUND.equals(action))
                    {
                        // Get the BluetoothDevice object from the Intent
                        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                        Log.d("DEVICE", "found "+ device.getName() + "  ++  " + device.getAddress());
                        // Add the name and address to an array adapter to show in a ListView
                        mDeviceList.add(device);
                        mDeviceNameList.add(device.getName() + "\n" + device.getAddress());
                        mArrayAdapter.notifyDataSetChanged();
                    }
                }
            };

            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            registerReceiver(mReceiver, filter); //TODO check if already registered, maybe unregister it at another position in code

        } catch (Exception e) {
            Toast.makeText(this,e.toString(), Toast.LENGTH_LONG).show();

        }
    }

    @Override
    protected void onPause() {
        Log.e("###", "onPause() called");
        Debug.stopMethodTracing();

        super.onPause();
        //unregisterReceiver(mReceiver);
    }

    @Override
    protected void onDestroy() {
        Log.e("###", "onDestroy() called");
        Debug.stopMethodTracing();

        super.onDestroy();
        unregisterReceiver(mReceiver);
    }

    BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            //gatt.requestConnectionPriority(1);
            Log.d(CONNECT_TAG, "onServiceDiscovered: " + gatt.getServices().toString());
            super.onServicesDiscovered(gatt, status);
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(CONNECT_TAG, "on connection state changed: status: " + status + " newState: " + newState);
            Log.d("MTU", "requested MTU success: " + gatt.requestMtu(212));
            super.onConnectionStateChange(gatt, status, newState);
        }
    };

    @Override
    public void onClick(View v) {

        //Try to establish a GATT connection for testing set MTU
        BluetoothGatt gatt = mSelectedDevice.connectGatt(this, false, gattCallback);
        //--------------------------------------------------------------

        if (v == btnConnect) {
            if (mDeviceList.size() > 0) {
                if (mSelectedDevice == null)
                    return;

                Log.d(CONNECT_TAG, "++address of device to connect: " + mSelectedDevice.getAddress());

                try {
                    selfDeviceAddress = mBluetoothAdapter.getAddress().getBytes("UTF-8");
                } catch (Exception e) {
                    Log.e(CONNECT_TAG, "Exception Adapter getAddress().getBytes()", e);
                }

                String macAddress = android.provider.Settings.Secure.getString(this.getContentResolver(), "bluetooth_address");

                Log.d(CONNECT_TAG, "++own device address from string: " + macAddress);
                Log.d(CONNECT_TAG, "++own device address from string: " + BluetoothAdapter.getDefaultAdapter().getAddress());

                Log.d(CONNECT_TAG, "own device address from string: " + selfDeviceAddress.toString());
                Log.d(CONNECT_TAG, "own device address from bytes: " + bytesToHex(selfDeviceAddress));

                //selectedDeviceAddress = mSelectedDevice.getAddress().getBytes("UTF-8");
                String[] bda = mSelectedDevice.getAddress().split(":");
                selectedDeviceAddress = new byte[6];
                for (int i = 0; i < bda.length; i++)
                    selectedDeviceAddress[i] = Integer.decode("0x" + bda[i]).byteValue();

                Log.d(CONNECT_TAG, "selected device address from string: " + mSelectedDevice.getAddress());
                Log.d(CONNECT_TAG, "selected device address from string: " + selectedDeviceAddress.toString());
                Log.d(CONNECT_TAG, "selected device address from bytes: " + bytesToHex(selectedDeviceAddress));

                //-------------------------------------------

                ConnectThread connThread = new ConnectThread(this, mSelectedDevice);
                bluetoothConnections.add(connThread);
                connThread.start();

            } else
                Log.e(CONNECT_TAG, "++can't connect to device, mDeviceList is empty!");
        } else if (v == btnRefresh) {

            mDeviceList.clear();

            ScanCallback scb = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    super.onScanResult(callbackType, result);
                    BluetoothDevice btDevice = result.getDevice();

                    Log.d("DEVICE", "found " + btDevice.getName() + "  ++  " + btDevice.getAddress());

                    // Add name and address to array adapter to show in ListView
                    if (!existsInDeviceList(btDevice)) {
                        mDeviceList.add(btDevice);
                        mDeviceNameList.add(btDevice.getName() + "\n" + btDevice.getAddress());
                        mArrayAdapter.notifyDataSetChanged();
                    }
                }
            };

            if (!refreshing) {
                refreshing = true;
                //TODO: add timer for scan-time

                if (mBLEScanner == null) {
                    mBLEScanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();
                }
                this.mBLEScanner.startScan(scb);
            }
            else {
                mBLEScanner.stopScan(scb);
            }
        }
    }

    private boolean existsInDeviceList(BluetoothDevice btDevice) {
        for (BluetoothDevice dev : mDeviceList) {
            if (dev.getAddress() == btDevice.getAddress())
                return true;
        }
        return false;
    }

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}


