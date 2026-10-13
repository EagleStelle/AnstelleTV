<script lang="ts">
	import type { Snippet } from 'svelte';

	/**
	 * Buttons on one line at their own width, lined up with the rows' text; on a phone they share
	 * the line evenly, as the app's settings do. [foot] is the page's foot, apart under a hairline:
	 * where a destructive verb waits.
	 */
	let { children, foot = false }: { children: Snippet; foot?: boolean } = $props();
</script>

<div
	class={[
		'flex flex-wrap gap-2 max-[599px]:*:min-w-fit max-[599px]:*:flex-1',
		foot ? 'mx-4 mt-10 border-t border-line pt-6' : 'px-4 py-3'
	]}
>
	{@render children()}
</div>
