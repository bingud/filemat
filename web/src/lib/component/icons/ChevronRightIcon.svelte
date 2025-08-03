<script lang="ts">
    import SvgUse from "./util/SvgUse.svelte";

    let {classes}: {classes?: string} = $props()
</script>
<SvgUse id="chevron-right" classes={classes}></SvgUse>