import React from 'react';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Layout from '@theme/Layout';

export default function Home() {
  const gettingStartedUrl = useBaseUrl('/docs/getting-started');

  return (
    <Layout title="Home" description="Type-safe AI function calling for Scala 3">
      <div style={{padding: '4rem', textAlign: 'center'}}>
        <h1>Constellations</h1>
        <p>Type-safe AI function calling for Scala 3</p>
        <Link to={gettingStartedUrl} className="button button--primary button--lg">
          Get Started
        </Link>
      </div>
    </Layout>
  );
}
