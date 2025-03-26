package org.filemat.server.module.user.model



/**
 * # DO NOT REORDER ENUM
 */
enum class UserAction {
    NONE,                                   // 0
    REGISTER,                               // 1
    LOGIN,                                  // 2
    GENERIC_ACCOUNT_CREATION,               // 3
    APP_SETUP,                              // 4
    AUTH,                                   // 5
    GENERIC_GET_ACCOUNT_ROLES,              // 6
    GENERIC_GET_PRINCIPAL,                  // 7
    READ_FOLDER,                            // 8
    LIST_USERS,                             // 9
    ASSIGN_ROLE,                            // 9
    UNASSIGN_ROLES,                         // 10
    CREATE_ROLE,                            // 11
    CREATE_USER,                            // 12
    GET_USER,                               // 13
    CHECK_USER_EXISTENCE                    // 14
}