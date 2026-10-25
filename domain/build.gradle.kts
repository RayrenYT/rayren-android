plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
dependencies {
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    testImplementation("junit:junit:4.13.2")
}
