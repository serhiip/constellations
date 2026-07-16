/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  tutorialSidebar: [
    'getting-started',

    {
      type: 'category',
      label: 'Common',
      collapsed: false,
      items: [
        'tool-dispatcher',
        'value-and-types',
        'encoding-decoding',
        'messages',
        'observability',
      ],
    },

    {
      type: 'category',
      label: 'Core',
      collapsed: false,
      link: {
        type: 'doc',
        id: 'core-concepts',
      },
      items: [
        'executor',
        'memory',
        'invoker',
        'handling',
        'files',
      ],
    },

    {
      type: 'category',
      label: 'LLM Providers',
      collapsed: false,
      link: {
        type: 'generated-index',
        title: 'LLM Providers',
        description: 'Integrations with LLM services',
        slug: '/llm-providers',
      },
      items: [
        'openrouter',
        'google-genai',
        'gcp-rag-engine',
      ],
    },

    'mcp',

    {
      type: 'category',
      label: 'Examples',
      collapsed: true,
      items: [
        'examples',
      ],
    },

    {
      type: 'link',
      label: 'API Reference',
      href: 'pathname:///api/index.html',
    },
  ],
};

module.exports = sidebars;
