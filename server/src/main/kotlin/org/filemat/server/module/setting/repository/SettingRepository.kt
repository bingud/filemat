package org.filemat.server.module.setting.repository

import org.filemat.server.module.setting.model.Setting
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SettingRepository : CrudRepository<Setting, String> {

    @Query("SELECT * FROM settings WHERE name = :name")
    fun getSetting(name: String): Setting?

    @Modifying
    @Query("INSERT OR REPLACE INTO settings (name, value, created_date) VALUES (:name, :value, :now)")
    fun setSetting(name: String, value: String, now: Long)

    @Modifying
    @Query("DELETE FROM settings WHERE name = :name")
    fun removeSetting(name: String)
}