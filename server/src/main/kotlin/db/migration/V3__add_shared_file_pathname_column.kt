package db.migration

class V3__add_shared_file_pathname_column : Migration() {
    override fun execute() = "ALTER TABLE shared_files ADD COLUMN pathname TEXT"
}