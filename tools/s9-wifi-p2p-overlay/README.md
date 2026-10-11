# S9 Android 12 Wi-Fi Direct compatibility

On the tested S9+ ROM, Wi-Fi Direct creates `p2p-wlan0-0`, but Tethering's
`config_tether_wifi_p2p_regexs` accepts only `p2p-p2p\d-.*` and `p2p\d`.
The resulting log is `Tried to Tether an unknown iface: p2p-wlan0-0` and the
group receives no IPv4 address. This overlay adds `p2p-wlan\d-.*` while
preserving the defaults. It targets the ROM's `TetheringConfig` overlayable.

Compile the resources with Android SDK aapt2, link against android.jar, sign
the resource-only APK, and place it in a Magisk module at
`system/product/overlay/S9WifiP2pFix.apk` with module.prop alongside `system`.
Reboot to mount the overlay. Verify `dumpsys tethering` includes the new regex,
then recreate the Open Head Unit group and verify its IPv4 address and DHCP.
Creating a group alone does not verify Android Auto projection.

Disable/remove Magisk module `s9_wifi_p2p_interface_fix` and reboot to revert.
This fix is specific to the observed interface mismatch; it does not bypass
permissions or disable SELinux.
