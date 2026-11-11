import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.ReconnectPolicy
import module_bindings.RemoteModule

fun main() {
    println("Community SDK consumer: generated with CLI ${RemoteModule.cliVersion}")
    println("Configured retry limit: ${ReconnectPolicy().maxRetries}")
}
