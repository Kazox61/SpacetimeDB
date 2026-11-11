import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.bsatn.BsatnReader
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.bsatn.BsatnWriter
import module_bindings.QueryBuilder
import module_bindings.RemoteModule
import module_bindings.RemoteProcedures
import module_bindings.RemoteReducers
import module_bindings.RemoteTables
import module_bindings.myLib.LibCountProcedure
import module_bindings.myLib.LibDataTableHandle
import module_bindings.myLib.LibInsertReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import module_bindings.EnvSqlRow as RootEnvSqlRow
import module_bindings.myLib.EnvSqlRow as LibEnvSqlRow

class SubmoduleCodegenTest {

    @Test
    fun `wire names are canonical and qualified once`() {
        assertEquals("my_lib.libData", LibDataTableHandle.TABLE_NAME)
        assertEquals("my_lib.lib_insert", LibInsertReducer.REDUCER_NAME)
        assertEquals("my_lib.lib_count", LibCountProcedure.PROCEDURE_NAME)
    }

    @Test
    fun `query builder quotes each namespace part`() {
        assertEquals("SELECT * FROM \"my_lib\".\"libData\"", QueryBuilder().myLib.libData().toSql())
    }

    @Test
    fun `root module covers submodule items`() {
        assertTrue("my_lib.libData" in RemoteModule.subscribableTableNames)
        assertTrue("my_lib.lib_insert" in RemoteModule.reducerNames)
        assertTrue("my_lib.lib_count" in RemoteModule.procedureNames)
    }

    @Test
    fun `accessors nest under the accessor namespace`() {
        // Compile-time checks of the API shape; nothing here needs a connection.
        val table: (RemoteTables) -> LibDataTableHandle = { it.myLib.libData }
        val reducer: (RemoteReducers) -> Unit = { it.myLib.libInsert("value") }
        val procedure: (RemoteProcedures) -> Unit = { it.myLib.libCount() }
        assertTrue(listOf(table, reducer, procedure).size == 3)
    }

    @Test
    fun `equal type names in root and submodule stay distinct`() {
        assertNotEquals<Any>(RootEnvSqlRow::class, LibEnvSqlRow::class)
        val writer = BsatnWriter()
        LibEnvSqlRow(key = "k", value = "v").encode(writer)
        assertEquals(LibEnvSqlRow(key = "k", value = "v"), LibEnvSqlRow.decode(BsatnReader(writer.toByteArray())))
    }
}
