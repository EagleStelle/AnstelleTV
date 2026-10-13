<script lang="ts">
	import type { Overview } from '#lib/api.ts';
	import { kindIcon } from '#lib/ondemand.ts';
	import ViewCarousel from '~icons/material-symbols/view-carousel';
	import Figure from './Figure.svelte';
	import Figures from './Figures.svelte';
	import Readout from './Readout.svelte';
	import ReadoutHeading from './ReadoutHeading.svelte';
	import Rule from './Rule.svelte';

	/** Each tab's banner size as figures, then each catalog tab's first rows, numbered, under its name. */
	let { onDemand }: { onDemand: Overview['onDemand'] } = $props();
</script>

<Readout>
	<ReadoutHeading icon={ViewCarousel} name="Banners" />
	<Figures>
		<Figure value={onDemand.homeBanner} name="Channels" />
		{#each onDemand.kinds as kind (kind.key)}<Figure value={kind.banner} name={kind.label} />{/each}
	</Figures>
	<Rule />
	<!-- Each tab under its name, its first rows numbered as the tab shows them. -->
	<div class="grid gap-3.5">
		{#each onDemand.kinds as kind (kind.key)}
			<div class="flex min-w-0 flex-col gap-1.5">
				<ReadoutHeading icon={kindIcon(kind.key)} name={kind.label} />
				{#if kind.firstRows.length === 0}
					<p class="truncate text-body-m text-text-muted">None yet</p>
				{:else}
					<ol class="flex flex-col gap-1">
						{#each kind.firstRows as row, i (row)}
							<li class="flex min-w-0 items-baseline gap-2 text-body-m">
								<span class="tabular w-3 shrink-0 text-text-muted">{i + 1}</span>
								<span class="truncate text-text">{row}</span>
							</li>
						{/each}
					</ol>
				{/if}
			</div>
		{/each}
	</div>
</Readout>
