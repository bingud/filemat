package org.filemat.server.module.user.model



/**
 * # DO NOT REORDER ENUM
 */
enum class UserAction(val index: Int) {
    NONE(0),
    REGISTER(1),
    LOGIN(2),
    GENERIC_ACCOUNT_CREATION(3),
    APP_SETUP(4),
    AUTH(5),
    GENERIC_GET_ACCOUNT_ROLES(6),
    GENERIC_GET_PRINCIPAL(7),
    READ_FOLDER(8),
    LIST_USERS(9),
    ASSIGN_ROLE(10),
    UNASSIGN_ROLES(11),
    CREATE_ROLE(12),
    CREATE_USER(13),
    GET_USER(14),
    CHECK_USER_EXISTENCE(15),
    UPDATE_ROLE_PERMISSIONS(16),
    DELETE_ROLE(17),
    GET_ENTITY_PERMISSIONS(18),
    CREATE_ENTITY_PERMISSION(19),
    UPDATE_ENTITY_PERMISSION(20),
    DELETE_ENTITY_PERMISSION(21),
    UPLOAD_FILE(22),
    UPDATE_SYSTEM_SETTING(23),
    CREATE_FOLDER(24),
    CREATE_FILE(25),
    DELETE_FILE(26),
    MOVE_FILE(27),
    GET_PERMITTED_ENTITIES(28),
    UPDATE_MFA(29),
    LOGOUT(30),
    VERIFY_LOGIN_TOTP_MFA(31),
    ENABLE_TOTP_MFA(32),
    DISABLE_TOTP_MFA(33);

    companion object {
        init {
            val codes = entries.map { it.index }
            require(codes.distinct().size == codes.size) {
                "Duplicate UserAction indexes found: $codes"
            }

            val sorted = codes.sorted()
            val expected = (sorted.first()..sorted.last()).toList()
            require(sorted == expected) {
                "UserAction indexes must be consecutive without gaps. Found: $sorted"
            }
        }
    }
}