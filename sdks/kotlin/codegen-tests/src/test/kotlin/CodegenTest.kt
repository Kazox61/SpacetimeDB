import module_bindings.UnitStruct
import module_bindings.UnitTestRow
import module_bindings.IndexedRow
import module_bindings.QueryBuilder
import module_bindings.RemoteModule
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.InternalSpacetimeApi
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.Query
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.bsatn.BsatnReader
import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.bsatn.BsatnWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse

class CodegenTest {

    @Test
    @OptIn(InternalSpacetimeApi::class)
    fun `parameterized view has a typed query accessor`() {
        val query: Query<IndexedRow> = QueryBuilder().indexedRowsByName("O'Brien")
        assertEquals("SELECT * FROM \"indexed_rows_by_name\"('O''Brien')", query.toSql())
        assertFalse("indexed_rows_by_name" in RemoteModule.subscribableTableNames)
    }

    @Test
    fun `empty product type is data object`() {
        // data object: equals by identity, singleton
        assertSame(UnitStruct, UnitStruct)
        assertEquals(UnitStruct.toString(), "UnitStruct")
    }

    @Test
    fun `empty product type round trips`() {
        val writer = BsatnWriter()
        UnitStruct.encode(writer)
        val bytes = writer.toByteArray()
        // Empty struct encodes to zero bytes
        assertEquals(0, bytes.size)

        val decoded = UnitStruct.decode(BsatnReader(bytes))
        assertSame(UnitStruct, decoded)
    }

    @Test
    fun `table with empty product type round trips`() {
        val row = UnitTestRow(id = 42u, value = UnitStruct)
        val writer = BsatnWriter()
        row.encode(writer)
        val decoded = UnitTestRow.decode(BsatnReader(writer.toByteArray()))
        assertEquals(row.id, decoded.id)
        assertSame(UnitStruct, decoded.value)
    }
}
