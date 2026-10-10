{
  description = "marola-app — marola's Scala 3 / Kyo pipeline, CLI, MCP server and image (MIP-0070)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    # Tools, the just module and the lint toolchain. Bump with .github/workflows/*.yml's @tag.
    marola-devkit = {
      url = "github:marola-dev/marola-devkit/v0.8.0";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, marola-devkit }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        devkit = marola-devkit.lib.${system};
        # Kyo's jars need JDK 25 (AGENTS.md). nixpkgs' sbt wrapper hardcodes its own JAVA_HOME at
        # build time, so it is rebuilt on this JDK rather than trusting PATH.
        jdk = pkgs.jdk25;
        sbtOnJdk25 = pkgs.sbt.override { jre = jdk; };
      in
      {
        devShells.default = pkgs.mkShell {
          name = "marola-app";
          packages = devkit.tools ++ [
            jdk
            sbtOnJdk25
            pkgs.scala-cli
            pkgs.coursier
            pkgs.python3
            # The local LLM/vision backend; `ollama serve` runs separately (RUN-LOCALLY.md).
            pkgs.ollama
          ];
          JAVA_HOME = "${jdk}";
          shellHook = devkit.shellHook + ''
            git config core.hooksPath .devkit/.githooks 2>/dev/null || true
            java -version
            echo "marola-app dev shell. Run 'just' to see available commands."
          '';
        };
      });
}
