<script lang="ts">
    import { downloadChooserState } from "$lib/code/stateObjects/subState/utilStates.svelte"
    import CustomDialog from "./CustomDialog.svelte"

    type DownloadChooserResult =
        | { kind: "cancel" }
        | { kind: "zip" }
        | { kind: "folder", handle: FileSystemDirectoryHandle }

    const AUTO_ZIP_SECONDS = 15

    let remainingSeconds = $state(AUTO_ZIP_SECONDS)
    let resolvePromise: ((value: DownloadChooserResult) => void) | null = $state(null)
    let timerId: ReturnType<typeof setInterval> | null = null
    let deadlineMs = 0

    export function show(): Promise<DownloadChooserResult> {
        remainingSeconds = AUTO_ZIP_SECONDS
        downloadChooserState.isOpen = true
        startTimer()

        return new Promise<DownloadChooserResult>((resolve) => {
            resolvePromise = resolve
        })
    }

    function startTimer() {
        clearTimer()
        deadlineMs = Date.now() + AUTO_ZIP_SECONDS * 1000
        remainingSeconds = AUTO_ZIP_SECONDS
        timerId = setInterval(() => {
            const left = Math.max(0, Math.ceil((deadlineMs - Date.now()) / 1000))
            remainingSeconds = left
            if (left <= 0) {
                finish({ kind: "zip" })
            }
        }, 200)
    }

    function clearTimer() {
        if (timerId !== null) {
            clearInterval(timerId)
            timerId = null
        }
    }

    function finish(result: DownloadChooserResult) {
        if (!resolvePromise) return
        clearTimer()
        const resolve = resolvePromise
        resolvePromise = null
        downloadChooserState.isOpen = false
        resolve(result)
    }

    function handleCancel() {
        finish({ kind: "cancel" })
    }

    function handleZip() {
        finish({ kind: "zip" })
    }

    async function handleFiles() {
        clearTimer()
        try {
            const picker = (window as Window & {
                showDirectoryPicker?: (options?: { mode?: "read" | "readwrite" }) => Promise<FileSystemDirectoryHandle>
            }).showDirectoryPicker
            if (!picker) {
                finish({ kind: "zip" })
                return
            }
            const handle = await picker({ mode: "readwrite" })
            finish({ kind: "folder", handle })
        } catch {
            // Picker dismissed — keep the chooser open and restart the auto-zip timer.
            if (downloadChooserState.isOpen && resolvePromise) {
                startTimer()
            }
        }
    }

    function handleClose() {
        if (downloadChooserState.isOpen && resolvePromise) {
            finish({ kind: "cancel" })
        }
    }

    $effect(() => {
        if (!downloadChooserState.isOpen && resolvePromise) {
            clearTimer()
            const resolve = resolvePromise
            resolvePromise = null
            resolve({ kind: "cancel" })
        }
    })
</script>

<CustomDialog
    bind:isOpen={downloadChooserState.isOpen}
    onOpenChange={handleClose}
    title="Choose download method"
    description={`ZIP download will start in ${remainingSeconds}s.`}
    class="w-[30rem]!"
>
    <div class="flex justify-end gap-2 flex-wrap">
        <button
            type="button"
            onclick={handleCancel}
            class="basic-button"
        >
            Cancel
        </button>
        <button
            type="button"
            onclick={handleZip}
            class="basic-button"
        >
            Zip
        </button>
        <button
            type="button"
            onclick={handleFiles}
            class="basic-button bg-surface-content-button!"
        >
            Files
        </button>
    </div>
</CustomDialog>
