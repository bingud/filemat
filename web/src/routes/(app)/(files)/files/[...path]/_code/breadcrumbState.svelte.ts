import { uiState } from "$lib/code/stateObjects/uiState.svelte"
import { arrayRemove, forEachReversed, generateRandomNumber, removeString } from "$lib/code/util/codeUtil.svelte"
import { calculateTextWidth, remToPx } from "$lib/code/util/uiUtil"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"

export type Segment = { name: string, path: string, width: number }

class BreadcrumbState {
    nonce = generateRandomNumber()
    containerWidth = $state(0) as number

    fullList = $derived.by(() => {
        uiState.screenWidth
        
        const allSegmens = filesState.segments
        
        let breadcrumbs = filesState.segments.map((seg, index) => {
            const width = calculateTextWidth(seg)
            const fullPath = filesState.segments.slice(0, index + 1).join("/")
            return { name: seg, path: fullPath, width: width }
        })

        // Add parent folder to breadcrumbs when shared file is open
        if (filesState.getIsShared()) {
            const width = calculateTextWidth(filesState.meta.shareTopLevelFilename)
            breadcrumbs.unshift({ name: filesState.meta.shareTopLevelFilename, path: "/", width: width })
        }
        return breadcrumbs
    })

    private list = $derived.by(() => {
        const totalAdditionalWidth = remToPx(1) + remToPx(1)
        const fullSegments = this.fullList
        
        const visible: Segment[] = []
        const hidden: Segment[] = []
        
        const hiddenSegmentsButton = calculateTextWidth('...') + remToPx(1)
        let width = 0 + hiddenSegmentsButton
        let outOfSpace = false
        
        forEachReversed(fullSegments, (seg, index) => {
            if (outOfSpace) {
                hidden.push(seg)
                return
            }
            
            const segmentWidth = index === 0 ? seg.width : seg.width + totalAdditionalWidth
            width += segmentWidth
            if (width > this.containerWidth && index !== 0) {
                hidden.push(seg)
                outOfSpace = true
            } else {
                visible.push(seg)
            }
        })
        
        if (visible.length === 0 && hidden.length > 1) {
            const path = hidden[0]
            arrayRemove(hidden, p => p === path)
            visible.push(path)
        }
        
        return { hidden: hidden, visible: visible.reverse() }
    })
    
    visible = $derived(this.list.visible)
    hidden = $derived(this.list.hidden)
}

export let breadcrumbState: BreadcrumbState

/**
 * @returns class nonce
 */
export function createBreadcrumbState(): number {
    breadcrumbState = new BreadcrumbState()
    return breadcrumbState.nonce
}

export function destroyBreadcrumbState(nonce: number) {
    if (breadcrumbState?.nonce === nonce) {
        breadcrumbState = undefined!
    }
}