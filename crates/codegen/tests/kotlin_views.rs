use spacetimedb_codegen::{generate, CodegenOptions, Kotlin};
use spacetimedb_lib::db::raw_def::v10::{RawModuleDefV10Builder, RawModuleDefV10Section, RawSubmoduleV10};
use spacetimedb_lib::{AlgebraicType, ProductType};
use spacetimedb_schema::def::ModuleDef;
use std::collections::HashMap;

#[test]
fn kotlin_parameterized_views_require_arguments_and_skip_subscribe_all() {
    let fixture = || {
        let mut builder = RawModuleDefV10Builder::new();
        let row = builder.add_algebraic_type([], "Entry", AlgebraicType::product([("id", AlgebraicType::U64)]), true);
        let rows = AlgebraicType::array(AlgebraicType::Ref(row));
        builder.add_view("all_entries", 0, true, true, ProductType::unit(), rows.clone());
        builder.add_view(
            "entries_by_owner",
            1,
            true,
            false,
            ProductType::from([
                ("owner", AlgebraicType::identity()),
                ("when", AlgebraicType::timestamp()),
                ("label", AlgebraicType::String),
                ("payload", AlgebraicType::array(AlgebraicType::U8)),
                ("count", AlgebraicType::U64),
            ]),
            rows,
        );
        builder.finish()
    };
    let mut outer = fixture();
    outer
        .sections
        .push(RawModuleDefV10Section::Submodules(vec![RawSubmoduleV10 {
            namespace: "innerLib".into(),
            module: fixture(),
        }]));
    let mut raw = fixture();
    raw.sections
        .push(RawModuleDefV10Section::Submodules(vec![RawSubmoduleV10 {
            namespace: "outerLib".into(),
            module: outer,
        }]));
    let module: ModuleDef = raw.try_into().unwrap();
    let files: HashMap<_, _> = generate(&module, &Kotlin, &CodegenOptions::default())
        .into_iter()
        .map(|file| (file.filename, file.code))
        .collect();

    for (filename, wire_name) in [
        ("Module.kt", "entries_by_owner"),
        ("outerLib/QueryBuilder.kt", "outer_lib.entries_by_owner"),
        (
            "outerLib/innerLib/QueryBuilder.kt",
            "outer_lib.inner_lib.entries_by_owner",
        ),
    ] {
        let code = &files[filename];
        assert!(code.contains("import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.type.Identity"));
        assert!(code.contains("import com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.type.Timestamp"));
        assert!(code.contains("fun entriesByOwner(owner: Identity, `when`: Timestamp, label: String, payload: ByteArray, count: ULong): ViewCall<Entry>"));
        assert!(code.contains(&format!("ViewCall(\"{wire_name}\", listOf(SqlLit.identity(owner), SqlLit.timestamp(`when`), SqlLit.string(label), SqlLit.bytes(payload), SqlLit.ulong(count)))")));
        assert!(!code.contains("fun entriesByOwner()"));
        assert!(code.contains("fun allEntries(): Table<Entry,"));
    }

    let root = &files["Module.kt"];
    let subscribable = root
        .split("override val subscribableTableNames")
        .nth(1)
        .unwrap()
        .split(')')
        .next()
        .unwrap();
    assert!(subscribable.contains("\"all_entries\""));
    assert!(subscribable.contains("\"outer_lib.inner_lib.all_entries\""));
    assert!(!subscribable.contains("entries_by_owner"));
    let subscribe_all = root
        .split("fun SubscriptionBuilder.subscribeToAllTables()")
        .nth(1)
        .unwrap();
    assert!(subscribe_all.contains("addQuery(qb.allEntries().toSql())"));
    assert!(subscribe_all.contains("addQuery(qb.outerLib.innerLib.allEntries().toSql())"));
    assert!(!subscribe_all.contains("entriesByOwner"));
    // Explicit subscriptions must still have row caches, including in nested submodules.
    assert!(root.contains("cache.register(EntriesByOwnerTableHandle.TABLE_NAME"));
    assert!(root.contains("cache.register(module_bindings.outerLib.innerLib.EntriesByOwnerTableHandle.TABLE_NAME"));
}
