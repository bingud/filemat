<script lang="ts">
    import ChevronLeftIcon from "$lib/component/icons/ChevronLeftIcon.svelte";
    import ChevronRightIcon from "$lib/component/icons/ChevronRightIcon.svelte";
    import { onMount } from "svelte";

    let { side, requireLongHover, onclick, lastMousePositionState } = $props<{
        side: "left" | "right",
        requireLongHover: boolean,
        onclick: () => void,
        lastMousePositionState?: {x: number, y: number}
    }>()

    let hovering = $state(false)
    let longHovering = $state(false)
    let timeout: ReturnType<typeof setTimeout>
    let detectionElement: HTMLElement
    let ticking = false
    let lastMousePosition = lastMousePositionState || { x: 0, y: 0 }

    let showNav = $derived((hovering && !requireLongHover) || longHovering)

    function checkZone() {
        if (detectionElement) {
            const rect = detectionElement.getBoundingClientRect()
            const isInZone =
                lastMousePosition.x >= rect.left &&
                lastMousePosition.x <= rect.right &&
                lastMousePosition.y >= rect.top &&
                lastMousePosition.y <= rect.bottom

            if (!isInZone) {
                if (hovering) {
                    hovering = false
                    clearTimeout(timeout)
                    longHovering = false
                }
            } else if (!hovering) {
                hovering = true
                timeout = setTimeout(() => {
                    longHovering = true
                }, 500)
            }
        }
    }

    function handleMouseMove(e: MouseEvent) {
        if (ticking) return
        ticking = true
        lastMousePosition.x = e.clientX
        lastMousePosition.y = e.clientY

        requestAnimationFrame(() => {
            checkZone()
            ticking = false
        })
    }

    $effect(() => {
        detectionElement
        checkZone()
    })
</script>

<svelte:window onmousemove={handleMouseMove} />

<div
    bind:this={detectionElement}
    class="absolute top-0 h-full w-12 z-floating transition-opacity flex items-center {side === 'left'
        ? 'left-0'
        : 'right-0 justify-end'} {showNav
        ? 'opacity-100'
        : 'opacity-0'} pointer-events-none"
>
    <button
        {onclick}
        class="flex items-center justify-center size-full bg-surface rounded-lg cursor-pointer transition-all h-2/3"
        class:pointer-events-auto={showNav}
    >
        <div class="w-full p-3 opacity-50">
            {#if side === "left"}
                <ChevronLeftIcon />
            {:else}
                <ChevronRightIcon />
            {/if}
        </div>
    </button>
</div>