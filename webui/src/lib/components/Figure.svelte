<script lang="ts">
	import { number } from '#lib/format.ts';

	/**
	 * One figure: the number in tabular digits over its name; [lead] sets the readout's main figure a
	 * size up. A zero reads quiet; a [value] given as text ("<1") stands as it is.
	 */
	let { value, name, lead = false }: { value: number | string; name: string; lead?: boolean } = $props();
</script>

<div class="flex flex-col">
	<span class={['tabular font-semibold', lead ? 'text-headline-m' : 'text-title-l min-[840px]:text-headline-s', value === 0 ? 'text-text-muted' : 'text-text']}>
		{typeof value === 'number' ? number(value) : value}
	</span>
	<span class="field-label text-label-s whitespace-nowrap text-text-muted">{name}</span>
</div>
