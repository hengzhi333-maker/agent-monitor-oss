module.exports = {
  apps: [
    {
      name: 'agent-monitor-daemon',
      script: 'src/index.js',
      cwd: __dirname,
      env: {
        NODE_ENV: 'production',
      },
      out_file: './agent-monitor.out.log',
      error_file: './agent-monitor.err.log',
      time: true,
      restart_delay: 2000,
      max_restarts: 20,
    },
  ],
};
