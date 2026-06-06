import type { FullPublicUser } from "$lib/code/auth/types"
import type { ulid } from "$lib/code/types/types"

class UserPageState {
    user: FullPublicUser | null = $state(null)
    pageUserId: string | null = $state(null)

    selectingRoles = $state(false)
    selectedRoles: ulid[] = $state([])
    removingRoles = $state(false)
    deletingUser = $state(false)

    reset() {
        this.user = null
        this.selectedRoles = []
        this.selectingRoles = false
        this.removingRoles = false
        this.deletingUser = false
    }
}


export const userPageState = new UserPageState()