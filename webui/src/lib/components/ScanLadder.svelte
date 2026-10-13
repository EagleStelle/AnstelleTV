<script lang="ts">
	import { onMount } from 'svelte';

	/** A sync running: a lit run of segments stepping along the ladder, the way a set scans for channels. */
	let { segments = 24, run = 4 }: { segments?: number; run?: number } = $props();

	const CYCLE_MS = 1800;
	let head = $state(0);

	onMount(() => {
		// Without motion the run holds at the ladder's start.
		head = run;
		if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
		const start = performance.now();
		let frame = requestAnimationFrame(function step(now) {
			// Whole segments only, so the run snaps from one to the next instead of gliding.
			head = Math.floor((((now - start) % CYCLE_MS) / CYCLE_MS) * (segments + run));
			frame = requestAnimationFrame(step);
		});
		return () => cancelAnimationFrame(frame);
	});
</script>

<div class="flex h-1.5 gap-0.5" aria-hidden="true">
	{#each { length: segments } as _, i (i)}
		<span class={['flex-1', i >= head - run && i < head ? 'bg-accent' : 'bg-line']}></span>
	{/each}
</div>
