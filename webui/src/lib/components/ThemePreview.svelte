<script lang="ts">
	import type { ThemeSpec } from '#lib/api.ts';

	/**
	 * A theme drawn as the app would wear it: a banner, a strip of guide cells, a focused and a
	 * plain menu row and the two buttons. Its colors are the theme's own, not the page's; it only
	 * shows, nothing in it takes focus.
	 */
	let { spec }: { spec: ThemeSpec } = $props();

	const c = $derived(spec.colors as Record<string, string> & { guideRuns: string[] });
	const fonts: Record<string, string> = {
		CONDENSED: "'Barlow Condensed', sans-serif",
		SANS: "'Barlow', sans-serif",
		SERIF: 'Georgia, serif',
		MONO: 'ui-monospace, monospace'
	};
	const display = $derived(fonts[spec.typography.display] ?? fonts.SANS);
	const body = $derived(fonts[spec.typography.body] ?? fonts.SANS);
	const radius = $derived(`${spec.guide.cellRadiusDp}px`);
	const scanlines = $derived(spec.effects.scanlines ? spec.effects.scanlineAlpha : 0);
</script>

<div
	class="relative flex flex-col gap-2.5 overflow-hidden rounded-panel border p-3.5 leading-snug"
	aria-hidden="true"
	style:background={c.background}
	style:border-color={c.line}
	style:font-family={body}
	style:font-size="{14 * spec.typography.scale}px"
>
	<p class="truncate text-[1.7em] font-semibold" style:color={c.text} style:font-family={display}>{spec.name}</p>
	<div class="flex h-16 overflow-hidden rounded-panel border" style:background={c.banner} style:border-color={c.line}>
		<span class="grid w-16 place-items-center text-[1.7em] font-semibold" style:background={c.accent} style:color={c.onAccent} style:font-family={display}>101</span>
		<div class="flex min-w-0 flex-1 flex-col justify-center gap-0.75 px-2.5">
			<span class="flex items-center gap-1.5 font-display text-[11px] leading-3.5 font-semibold tracking-field" style:color={c.accent}>
				<i class="size-1.75" style:background={c.accent}></i>NOW <b class="font-semibold" style:color={c.textMuted}>8:00 – 8:30</b>
			</span>
			<span class="truncate font-semibold" style:color={c.bannerText}>The Late Show</span>
			<span class="flex h-1 gap-0.5">
				{#each Array(20) as _, i (i)}<i class="flex-1" style:background={i < 11 ? c.accent : c.line}></i>{/each}
			</span>
		</div>
	</div>
	<div class="flex h-10 gap-0.5 text-[0.85em]">
		<span class="flex flex-[0.8] items-center truncate px-2" style:background={c.guideCellPast} style:color={c.textMuted} style:border-radius={radius}>Aired</span>
		<span class="relative flex flex-[1.2] items-center gap-1.5 truncate px-2" style:background={c.guideCell} style:color={c.text} style:border-radius={radius}>
			<i class="size-1.75 shrink-0" style:background={c.accent}></i>On now
			<i class="absolute inset-y-0 left-0 w-0.5" style:background={c.nowLine}></i>
		</span>
		{#each c.guideRuns.slice(0, 2) as run, i (i)}
			<span class="flex flex-1 items-center truncate px-2" style:background={run} style:color={c.text} style:border-radius={radius}>{i === 0 ? 'Later' : 'Then'}</span>
		{/each}
	</div>
	<div class="flex h-11 items-center justify-between rounded-control px-3 font-medium" style:background={c.accent} style:color={c.onAccent}>
		<span>Focused row</span><b>On</b>
	</div>
	<div class="flex h-11 items-center justify-between rounded-control px-3 font-medium" style:background={c.surface} style:color={c.text}>
		<span>A row at rest</span><b>Off</b>
	</div>
	<div class="flex gap-2 *:grid *:h-10 *:flex-1 *:place-items-center *:rounded-control *:font-semibold">
		<span style:background={c.accent} style:color={c.onAccent}>Watch</span>
		<span style:background={c.surface} style:color={c.text}>Later</span>
	</div>
	<div class="flex items-center gap-1.5 text-[0.85em]">
		<span class="rounded-control px-1.5 py-0.5 font-display text-[11px] leading-3.5 font-semibold tracking-field" style:background={c.accentContainer} style:color={c.onAccentContainer}>
			SELECTED
		</span>
		<span style:color={c.textMuted}>Quiet text sits here.</span>
	</div>
	{#if scanlines > 0}
		<!-- A picture tube's lines: one dark pixel in every three, over everything. -->
		<div class="pointer-events-none absolute inset-0 bg-[repeating-linear-gradient(to_bottom,transparent_0_2px,#000_2px_3px)]" style:opacity={scanlines}></div>
	{/if}
</div>
