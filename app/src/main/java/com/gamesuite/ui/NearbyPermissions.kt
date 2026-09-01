package com.gamesuite.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime ("dangerous") permissions Nearby Connections needs to actually do
 * anything — startAdvertising/startDiscovery silently no-op without them. Computed per
 * API level rather than declaring one fixed list, since BLUETOOTH_SCAN/ADVERTISE/CONNECT
 * only exist from API 31 and NEARBY_WIFI_DEVICES only from API 33 — requesting a
 * permission the running OS doesn't know about would just be a no-op string, but there's
 * no reason to even try. Both real target devices (Fold 5, Tab S9) run Android 14+, so
 * the API 31+/33+ branch is what actually executes there.
 */
fun requiredNearbyPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
}.toTypedArray()

fun hasAllNearbyPermissions(context: Context): Boolean =
    requiredNearbyPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

fun isBluetoothEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return manager?.adapter?.isEnabled == true
}

fun isWifiEnabled(context: Context): Boolean {
    val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    return manager?.isWifiEnabled == true
}

/**
 * Both radios enabled — the pre-flight check DEVICE_SPECIFIC_PLAN.md §2 flags as urgent:
 * Google announced (July 2026) that Nearby Connections will stop auto-enabling Wi-Fi/
 * Bluetooth "in late 2026" — apps must check first and prompt the user themselves,
 * rather than relying on the API to silently turn radios on. Built now rather than
 * retrofitted later once that ships.
 */
fun radiosReady(context: Context): Boolean = isBluetoothEnabled(context) && isWifiEnabled(context)
