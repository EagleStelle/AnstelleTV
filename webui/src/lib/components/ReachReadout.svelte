<script lang="ts">
	import type { Reach } from '#lib/api.ts';
	import Figure from './Figure.svelte';
	import Figures from './Figures.svelte';
	import Ladder from './Ladder.svelte';
	import Readout from './Readout.svelte';

	/**
	 * What a lineup airs: its hours, series, episodes and movies as figures, then a ladder of the
	 * library's airtime with the lineup's share filled in, a step of the palette per kind.
	 */
	let { reach }: { reach: Reach | null } = $props();

	const parts = $derived(
		reach
			? [
					{ name: 'Episodes', share: reach.episodeShare, color: 'var(--color-accent)' },
					{ name: 'Movies', share: reach.movieShare, color: 'var(--color-text)' },
					{ name: 'Music videos', share: reach.videoShare, color: 'var(--color-text-muted)' }
				].filter((p) => p.share > 0)
			: []
	);
	const percent = (share: number) => Math.max(1, Math.round(share * 100));
	const empty = $derived(!reach || reach.episodes + reach.movies + reach.videos === 0);
</script>

<Readout>
	{#if !reach}
		<p class="text-text-muted">Counting what it airs…</p>
	{:else if empty}
		<p class="text-text-muted">Nothing in your library matches yet.</p>
	{:else}
		<Figures>
			<Figure value={reach.hours < 1 ? '<1' : reach.hours} name={reach.hours === 1 ? 'hour' : 'hours'} lead />
			{#if reach.series > 0}<Figure value={reach.series} name="series" />{/if}
			{#if reach.episodes > 0}<Figure value={reach.episodes} name={reach.episodes === 1 ? 'episode' : 'episodes'} />{/if}
			{#if reach.movies > 0}<Figure value={reach.movies} name={reach.movies === 1 ? 'movie' : 'movies'} />{/if}
			{#if reach.videos > 0}<Figure value={reach.videos} name={reach.videos === 1 ? 'music video' : 'music videos'} />{/if}
		</Figures>
		<Ladder {parts} label="{percent(parts.reduce((sum, p) => sum + p.share, 0))} percent of your library's airtime" />
		<div class="field-label flex flex-wrap gap-x-4 gap-y-1.5 whitespace-pre text-text-muted">
			{#each parts as part (part.name)}
				<span class="inline-flex items-center gap-1.5"><i class="size-2" style:background={part.color}></i>{part.name}  {percent(part.share)}%</span>
			{/each}
			<span>of your library’s airtime</span>
		</div>
	{/if}
</Readout>
