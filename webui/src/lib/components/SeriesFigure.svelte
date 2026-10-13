<script lang="ts">
	import { number } from '#lib/format.ts';
	import Figure from './Figure.svelte';

	/**
	 * Series and their episodes as one figure: the series count, then the episodes a size smaller,
	 * joined by an accent crossbar that runs from one number into the other the way the mark's
	 * crossbar runs on past the A. Episodes belong to series, so they never stand beside movies as a
	 * count of their own. The names share a baseline at the foot; the bar sits at the middle of the
	 * episodes' digits.
	 */
	let { series, episodes }: { series: number; episodes: number } = $props();
</script>

<div class="flex items-end">
	<Figure value={series} name="series" />
	<span class="flex w-7 flex-col px-1.5 min-[840px]:w-10 min-[840px]:px-2" aria-hidden="true">
		<span class="flex h-(--text-title-l--line-height) items-center min-[840px]:h-(--text-headline-s--line-height)"><span class="h-0.5 w-full bg-accent"></span></span>
		<span class="h-(--text-label-s--line-height)"></span>
	</span>
	<span class="flex flex-col">
		<span class="tabular text-title-l font-semibold min-[840px]:text-headline-s">{number(episodes)}</span>
		<span class="field-label text-label-s whitespace-nowrap text-text-muted">{episodes === 1 ? 'episode' : 'episodes'}</span>
	</span>
</div>
