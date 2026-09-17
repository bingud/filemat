import { forEachObject, valuesOf } from "$lib/code/util/codeUtil.svelte"

export type FileDownloadStatus = `queued` | `downloading` | `success` | `failed` | `canceled` | `skipped`

export type FileDownload = {
    path: string,
    jobId: string,
    displayPath: string | null,
    status: FileDownloadStatus,
    bytesTotal: number,
    bytesDownloaded: number,
    percentage: number,
    abortController: AbortController | null,
    fileHandle: FileSystemFileHandle | null,
    /** Captured at enqueue time; `null` means no share token. */
    shareToken: string | null,
}

export type DownloadJobKind = `file` | `folder`

export type DownloadJob = {
    id: string,
    kind: DownloadJobKind,
    displayPath: string,
    /** Nested file list for folder jobs; closed by default. */
    expanded: boolean,
    abortController: AbortController,
    /** True while this root is still walking the folder tree. */
    listing: boolean,
}

export type DownloadJobStats = {
    total: number,
    successful: number,
    downloading: number,
    failed: number,
    canceled: number,
    skipped: number,
    queued: number,
    bytesTotal: number,
    bytesDownloaded: number,
    status: FileDownloadStatus,
}

function emptyStats(): DownloadJobStats {
    return {
        total: 0,
        successful: 0,
        downloading: 0,
        failed: 0,
        canceled: 0,
        skipped: 0,
        queued: 0,
        bytesTotal: 0,
        bytesDownloaded: 0,
        status: `queued`,
    }
}

function aggregateStatus(s: Omit<DownloadJobStats, `status` | `bytesTotal` | `bytesDownloaded`>): FileDownloadStatus {
    if (s.downloading > 0) return `downloading`
    if (s.queued > 0) return `queued`
    if (s.failed > 0 && s.successful === 0 && s.canceled === 0 && s.skipped === 0) return `failed`
    if (s.canceled > 0 && s.successful === 0 && s.failed === 0 && s.skipped === 0) return `canceled`
    if (s.skipped > 0 && s.successful === 0 && s.failed === 0 && s.canceled === 0) return `skipped`
    if (s.successful > 0 && s.failed === 0 && s.canceled === 0) return `success`
    if (s.failed > 0) return `failed`
    if (s.canceled > 0) return `canceled`
    return `success`
}

export class DownloadState {
    /** Top-level panel rows (one per selected file or folder root). */
    jobs: Record<string, DownloadJob> = $state({})

    /** Leaf file transfers keyed by path. */
    files: Record<string, FileDownload> = $state({})

    list = $derived(valuesOf(this.jobs))

    count = $derived(valuesOf(this.jobs).length)

    /** In-flight folder-save batches (cancel-all aborts every one). */
    batchAborts: Set<AbortController> = new Set()

    counts = $derived.by(() => {
        let successful = 0
        let downloading = 0
        let canceled = 0
        let failed = 0
        let skipped = 0
        let queued = 0
        let total = 0

        forEachObject(this.files, (_k, v) => {
            total++
            if (v.status === `success`) successful++
            else if (v.status === `downloading`) downloading++
            else if (v.status === `canceled`) canceled++
            else if (v.status === `failed`) failed++
            else if (v.status === `skipped`) skipped++
            else if (v.status === `queued`) queued++
        })

        return {
            downloading,
            successful,
            failed,
            canceled,
            skipped,
            queued,
            total,
        }
    })

    get hasBlockingDownloads() {
        if (this.counts.downloading > 0 || this.counts.queued > 0) return true
        for (const job of valuesOf(this.jobs)) {
            if (job.listing && !job.abortController.signal.aborted) return true
        }
        return false
    }

    getFile(path: string): FileDownload | null {
        return this.files[path] ?? null
    }

    getJob(id: string): DownloadJob | null {
        return this.jobs[id] ?? null
    }

    filesForJob(jobId: string): FileDownload[] {
        const out: FileDownload[] = []
        forEachObject(this.files, (_k, v) => {
            if (v.jobId === jobId) out.push(v)
        })
        return out
    }

    jobStats(jobId: string): DownloadJobStats {
        const stats = emptyStats()

        forEachObject(this.files, (_k, v) => {
            if (v.jobId !== jobId) return
            stats.total++
            stats.bytesTotal += v.bytesTotal || 0
            stats.bytesDownloaded += v.bytesDownloaded || 0
            if (v.status === `success`) stats.successful++
            else if (v.status === `downloading`) stats.downloading++
            else if (v.status === `canceled`) stats.canceled++
            else if (v.status === `failed`) stats.failed++
            else if (v.status === `skipped`) stats.skipped++
            else if (v.status === `queued`) stats.queued++
        })

        stats.status = stats.total === 0 ? `success` : aggregateStatus(stats)
        return stats
    }

    addJob(
        id: string,
        options: {
            kind: DownloadJobKind,
            displayPath: string,
        },
    ): DownloadJob {
        this.panelExpanded = true
        this.panelOpen = true

        const existing = this.jobs[id]
        if (existing) {
            if (existing.abortController.signal.aborted) {
                existing.abortController = new AbortController()
            }
            existing.listing = true
            existing.kind = options.kind
            existing.displayPath = options.displayPath
            return existing
        }

        const job: DownloadJob = {
            id,
            kind: options.kind,
            displayPath: options.displayPath,
            expanded: false,
            abortController: new AbortController(),
            listing: true,
        }
        this.jobs[id] = job
        return job
    }

    addFile(
        path: string,
        options: {
            jobId: string,
            displayPath?: string | null,
            bytesTotal?: number,
            fileHandle?: FileSystemFileHandle | null,
            status?: FileDownloadStatus,
            shareToken?: string | null,
        },
    ): FileDownload | null {
        this.panelExpanded = true
        this.panelOpen = true

        const existing = this.files[path]
        if (existing && (existing.status === `downloading` || existing.status === `queued`)) {
            return null
        }

        const entry: FileDownload = {
            path,
            jobId: options.jobId,
            displayPath: options.displayPath ?? null,
            status: options.status ?? `queued`,
            bytesTotal: options.bytesTotal ?? 0,
            bytesDownloaded: 0,
            percentage: 0,
            abortController: null,
            fileHandle: options.fileHandle ?? null,
            shareToken: options.shareToken ?? null,
        }
        this.files[path] = entry
        return entry
    }

    removeFile(path: string) {
        delete this.files[path]
    }

    removeJob(jobId: string) {
        const job = this.jobs[jobId]
        job?.abortController.abort()
        forEachObject(this.files, (k, v) => {
            if (v.jobId === jobId) delete this.files[k]
        })
        delete this.jobs[jobId]
    }

    clearFinished() {
        forEachObject(this.jobs, (jobId, job) => {
            if (job.listing) return
            const stats = this.jobStats(jobId)
            if (stats.downloading > 0 || stats.queued > 0) return
            this.removeJob(jobId)
        })
    }

    panelOpen = $state(true)
    panelExpanded = $state(true)
}

export const downloadState = new DownloadState()
