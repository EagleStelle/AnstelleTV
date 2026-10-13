<script lang="ts">
	/**
	 * A short code (a Quick Connect code) as one row of number blocks: accent blocks with ink digits,
	 * sharing the width up to a cap so a narrow phone never pushes the code off screen. A null [code]
	 * draws hollow slots while it loads.
	 */
	let { code, length = 6 }: { code: string | null; length?: number } = $props();

	const slots = $derived(Array.from({ length: code?.length ?? length }, (_, i) => code?.[i] ?? null));
</script>

<div class="flex w-full max-w-80 gap-1.5 min-[840px]:max-w-[366px]" role="img" aria-label={code ? `Code ${code.split('').join(' ')}` : 'Getting a code'} aria-live="polite">
	{#each slots as char, i (i)}
		{#if char}
			<span class="tabular grid aspect-56/72 flex-1 place-items-center rounded-control bg-accent text-headline-l font-bold text-on-accent min-[840px]:text-display-m">{char}</span>
		{:else}
			<span class="aspect-56/72 flex-1 rounded-control border border-line"></span>
		{/if}
	{/each}
</div>
