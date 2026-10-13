<script lang="ts">
	/** A theme at a glance: its ground, rows, text and accent side by side. */
	let { colors }: { colors: string[] } = $props();
</script>

<span class="flex gap-0.5" aria-hidden="true">
	{#each colors as color, i (i)}
		<span class="inline-block size-4 rounded-control border border-line" style:background={color}></span>
	{/each}
</span>
