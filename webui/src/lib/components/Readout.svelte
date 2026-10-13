<script lang="ts">
	import type { Snippet } from 'svelte';

	/**
	 * A readout, the set's system-information panel the app's settings draw: a hairline frame with
	 * no fill (so it never reads as something to press), its parts a fixed rhythm apart. From 840 px
	 * it takes the TV's size, as the guided panel does. [roomy] is the server page's looser rhythm.
	 */
	let { children, roomy = false }: { children: Snippet; roomy?: boolean } = $props();
</script>

<div
	class={[
		'flex h-full flex-col rounded-panel border border-line p-4 min-[840px]:p-5',
		roomy ? 'gap-3.5 min-[840px]:gap-4' : 'gap-3 min-[840px]:gap-3.5'
	]}
>
	{@render children()}
</div>
