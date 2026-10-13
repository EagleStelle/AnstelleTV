<script lang="ts">
	/**
	 * A ladder of segments with 2 px gaps: each part fills its share of [segments] (at least one,
	 * so a sliver still shows), the rest stay empty.
	 */
	let { parts, segments = 40, label }: { parts: { share: number; color: string }[]; segments?: number; label: string } = $props();

	const colors = $derived.by(() => {
		const filled: string[] = [];
		for (const part of parts) {
			if (part.share <= 0) continue;
			const count = Math.max(1, Math.round(part.share * segments));
			for (let i = 0; i < count; i++) filled.push(part.color);
		}
		return Array.from({ length: segments }, (_, i) => filled[i] ?? 'var(--color-line)');
	});
</script>

<div class="flex h-2.5 gap-0.5" role="img" aria-label={label}>
	{#each colors as color, i (i)}
		<span class="flex-1" style:background={color}></span>
	{/each}
</div>
