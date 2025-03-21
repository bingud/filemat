<script lang="ts">
    import { debounceFunction } from "$lib/code/util/codeUtil.svelte";
    import { onMount, type Snippet } from "svelte";
    import { preventDefault } from "svelte/legacy";
    import { fade } from "svelte/transition";

    let { children, buttonId, marginRem = 0, fadeDuration = 0, open = false }: {
        children: Snippet<[]>,
        buttonId: string,
        marginRem?: number,
        fadeDuration?: number,
        open?: boolean
    } = $props()

    /**
     * Button to toggle popover
     */
    let button: HTMLElement | null = $state(null)
    let visible = $state(open)

    /**
     * Coordinations of top-middle of button
     */
    let topY: number | null = $state(null)
    let topX: number | null = $state(null)

    /**
     * Dimensions of popover itself
     */
    let popoverHeight = $state(0)
    let popoverWidth = $state(0)

    onMount(() => {
        button = document.getElementById(buttonId)
        if (button == null) return

        button.addEventListener('click', onClick)
        document.addEventListener('scroll', calcCoords, true)

        return () => {
            button?.removeEventListener('click', onClick)
            document.removeEventListener('scroll', calcCoords)
        }
    })

    $effect(() => { calcCoords(); button })

    /**
     * Function to calculate the location of the button
     */
    const calcCoords = debounceFunction(calculateCoordinates, 10, 40)
    function calculateCoordinates() {
        if (button) {
            const rect = button.getBoundingClientRect()
            topY = rect.top + window.scrollY
            topX = rect.left + rect.width / 2 + window.scrollX
        } else {
            topY = null
            topX = null
        }
    }

    /**
     * Handles button click
     */
    function onClick() {
        visible = !visible
        calcCoords()
    }

    /**
     * Hides popover
     */
    function hide() {
        visible = false
    }

    /**
     * Hack for layout shift
     */
    function onResize() {
        calcCoords()

        for (let i = 0; i < 20; i++) {
            setTimeout(() => {
                calcCoords()
            }, i * 75)
        }
    }

    /**
     * Handles escape key
     */
    function onKeydown(e: KeyboardEvent) {
        if (e.key === "Escape") {
            hide()
        }
    }
</script>

<svelte:window on:resize={onResize}></svelte:window>
<svelte:document on:keydown={onKeydown}></svelte:document>

{#if visible && button && topY != null && topX != null}
    <!-- Close popover by clicking off -->
    <button aria-label="Close the popover" class="z-10 fixed top-0 left-0 w-full h-full !cursor-default" on:wheel|passive={hide} on:mousedown={hide} on:click={hide}></button>

    <!-- Popover -->
    <div transition:fade={{duration: fadeDuration}} bind:offsetHeight={popoverHeight} bind:offsetWidth={popoverWidth} class="fixed z-20 rounded flex flex-col" style="top: {topY - popoverHeight}px; left: {topX - (popoverWidth / 2)}px; padding-bottom: {marginRem}rem;">
        {@render children()}
    </div>
{/if}