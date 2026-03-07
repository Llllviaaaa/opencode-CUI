import { BridgeRuntime } from './BridgeRuntime';
import type { PluginInput } from './types';

let runtime: BridgeRuntime | null = null;
let initializing: Promise<BridgeRuntime> | null = null;
let lifecycleAbortController: AbortController | null = null;
let generation = 0;

export async function getOrCreateRuntime(input: PluginInput): Promise<BridgeRuntime> {
  if (runtime) {
    return runtime;
  }

  if (initializing) {
    return initializing;
  }

  const candidate = new BridgeRuntime({
    workspacePath: input.worktree || input.directory,
    client: input.client,
  });
  const token = ++generation;
  lifecycleAbortController = new AbortController();

  initializing = candidate
    .start({ abortSignal: lifecycleAbortController.signal })
    .then(() => {
      if (token !== generation || lifecycleAbortController?.signal.aborted) {
        candidate.stop();
        throw new Error('runtime_initialization_cancelled');
      }
      runtime = candidate;
      return candidate;
    })
    .catch((error) => {
      runtime = null;
      throw error;
    })
    .finally(() => {
      initializing = null;
    });

  return initializing;
}

export function getRuntime(): BridgeRuntime | null {
  return runtime;
}

export function stopRuntime(): void {
  generation += 1;

  if (initializing) {
    lifecycleAbortController?.abort();
    initializing = null;
  }

  if (!runtime) {
    lifecycleAbortController = null;
    return;
  }

  runtime.stop();
  runtime = null;
  lifecycleAbortController = null;
}

export function __resetRuntimeForTests(): void {
  stopRuntime();
}
