import { uiState } from "$lib/code/stateObjects/uiState.svelte"
import { arrayRemove, forEachReversed, removeString } from "$lib/code/util/codeUtil.svelte"
import { calculateTextWidth, remToPx } from "$lib/code/util/uiUtil"
import { filesState } from "../../../../../../lib/code/stateObjects/filesState.svelte"

export type Segment = { name: string, path: string, width: number }

class BreadcrumbState {
    containerWidth = $state(0) as number

    private list = $derived.by(() => {
        uiState.screenWidth

        const chevronWidth = remToPx(1)
        const paddingWidth = remToPx(1)
        const totalAdditionalWidth = chevronWidth + paddingWidth
    
        const fullSegments = filesState.segments.map((seg, index) => {
            const width = calculateTextWidth(seg)
            const fullPath = filesState.segments.slice(0, index + 1).join("/")
            return { name: seg, path: fullPath, width: width }
        })
    
        const visible: Segment[] = []
        const hidden: Segment[] = []
    
        // width of visible breadcrumbs
        const hiddenSegmentsButton = calculateTextWidth('...') + paddingWidth
        let width = 0 + hiddenSegmentsButton
        let outOfSpace = false
    
        // calculate which breadcrumbs will be visible
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

export function createBreadcrumbState() {
    breadcrumbState = new BreadcrumbState()
}

export function destroyBreadcrumbState() {
    breadcrumbState = undefined!
}