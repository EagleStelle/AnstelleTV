<script lang="ts">
	/** A color as a flat square with a hairline, so a color close to the ground still shows its edge. */
	let { color, size = 24 }: { color: string; size?: number } = $props();
</script>

<span class="inline-block shrink-0 rounded-control border border-line" style:background={color} style:width="{size}px" style:height="{size}px" aria-hidden="true"></span>
