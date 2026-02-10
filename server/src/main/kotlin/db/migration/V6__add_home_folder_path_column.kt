package db.migration



class V6__add_home_folder_path_column : Migration() {
    override fun execute() = "ALTER TABLE users ADD COLUMN home_folder_path TEXT DEFAULT NULL"
}