plugins {
    alias(libs.plugins.android.application) apply false
}

// The project lives in a OneDrive-synced folder; keep build output local so OneDrive doesn't sync thousands of build files.
allprojects {
    layout.buildDirectory.set(File(System.getProperty("user.home"), ".gradle-builds/hendrifs/${project.name}"))
}
