'use strict';

/**
 * Remote blocklist sources.
 * These are downloaded daily and merged with the curated local lists.
 * Set enabled: false to skip a source without deleting it.
 */
module.exports = [
  {
    name: 'hagezi-multi-normal',
    description: 'HaGeZi Multi Normal — comprehensive multi-purpose blocklist',
    url: 'https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/domains/multi.txt',
    format: 'plain',
    enabled: true,
  },
  {
    name: 'stevenblack-unified',
    description: 'Steven Black unified hosts (ads + malware)',
    url: 'https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts',
    format: 'hosts',
    enabled: true,
  },
  {
    name: 'oisd-small',
    description: 'OISD Small — curated small blocklist, very low false positives',
    url: 'https://small.oisd.nl/domainswild',
    format: 'plain',
    enabled: false, // opt-in: large list, enable if you want broader coverage
  },
  {
    name: 'easylist-adservers',
    description: 'EasyList ad servers in hosts format',
    url: 'https://raw.githubusercontent.com/FadeMind/hosts.extras/master/UncheckyAds/hosts',
    format: 'hosts',
    enabled: false,
  },
];
