<script lang="ts">
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { formData, handleError, handleErrorResponse, handleException, handleServerDownError, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";

    const title = "Exposed files"

    onMount(() => {
        uiState.settings.title = title
    })

    type VisibilityMap = { [key: string]: boolean }

    let visibilities: VisibilityMap | null = $state(null)

    async function loadVisibilities() {
        const response = await safeFetch(`/api/v1/admin/system/file-visibility-entries`, { method: "GET" })
        if (response.failed) {
            visibilities = null
            handleException(`failed to load file visibilities`, `Failed to load list of exposed files.`, response.exception)
            return
        }

        const status = response.code
        const json = response.json()
        if (status.ok) {
            visibilities = json
        } else {
            handleError(`Failed to load file visibilities`, json.message || "Failed to load list of exposed files.", status.serverDown)
        }
    }
</script>

<div class="flex flex-col gap-4">
    <div class="flex flex-col gap-2">
        <h3 class="font-medium">Exposed files</h3>
        <p class="text-sm text-neutral-600 dark:text-neutral-400">
            Configure which files and folders are exposed through Filemat.
        </p>
    </div>
</div>