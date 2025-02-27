<script lang="ts">
    let props: { class?: string } = $props()
</script>


<div class="loader {props.class ?? ""}"></div>