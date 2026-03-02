import ExecutionEnvironment from '@docusaurus/ExecutionEnvironment';

export default function prismIncludeLanguages(Prism) {
  if (!ExecutionEnvironment.canUseDOM) {
    return;
  }

  if (!Prism || Prism.languages.scala) {
    return;
  }

  const previousPrism = globalThis.Prism;
  globalThis.Prism = Prism;
  try {
    // Scala grammar depends on clike/java being available first.
    require('prismjs/components/prism-clike');
    require('prismjs/components/prism-java');
    require('prismjs/components/prism-scala');
  } finally {
    if (previousPrism) {
      globalThis.Prism = previousPrism;
    } else {
      delete globalThis.Prism;
    }
  }
}
