allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// Igual que la plantilla oficial de Flutter: mover los buildDir bajo <repo>/build
// para que `flutter build apk` encuentre la APK en build/app/outputs/flutter-apk.
rootProject.buildDir = file("../build")
subprojects {
    project.buildDir = file("${rootProject.buildDir}/${project.name}")
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
