adb shell am force-stop com.chetbox.mousecursor
adb shell settings put secure enabled_accessibility_services com.chetbox.mousecursor/.MouseAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell am start -n com.chetbox.mousecursor/.MainActivity


