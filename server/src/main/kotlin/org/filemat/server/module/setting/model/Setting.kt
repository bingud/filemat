package org.filemat.server.module.setting.model

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("settings")
data class Setting(
    @Column("name")
    val name: String,
    @Column("value")
    val value: String,
    @Column("created_date")
    val updatedDate: Long,
)