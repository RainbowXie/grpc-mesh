# Git Repository Setup Guide

This document explains the Git repository structure and how to complete the setup.

## Repository Structure

```
grpc-mesh/                  # Main umbrella repository
├── grpc-mesh-node/         # Rust framework (submodule)
└── grpc-mesh-server/       # Go control plane (submodule)
```

## Current Status

✅ **Completed:**
- Main repository `grpc-mesh` initialized with `main` branch
- Submodule `grpc-mesh-node` initialized with `master` branch
- Submodule `grpc-mesh-server` initialized with `main` branch
- All repositories have initial commits
- `.gitmodules` file created with placeholder URLs

⚠️ **Pending:**
- Set up remote repositories (GitHub/GitLab/etc.)
- Update submodule URLs
- Push all repositories to remotes

## How to Complete the Setup

### Option 1: Using GitHub (or similar platform)

1. **Create remote repositories:**
   ```bash
   # Create three repositories on GitHub:
   # - YOUR_USERNAME/grpc-mesh
   # - YOUR_USERNAME/grpc-mesh-node
   # - YOUR_USERNAME/grpc-mesh-server
   ```

2. **Push grpc-mesh-node:**
   ```bash
   cd grpc-mesh-node
   git remote add origin https://github.com/YOUR_USERNAME/grpc-mesh-node.git
   git branch -M main  # or keep as master
   git push -u origin main
   cd ..
   ```

3. **Push grpc-mesh-server:**
   ```bash
   cd grpc-mesh-server
   git remote add origin https://github.com/YOUR_USERNAME/grpc-mesh-server.git
   git push -u origin main
   cd ..
   ```

4. **Update .gitmodules with real URLs:**
   ```bash
   # Edit .gitmodules and replace YOUR_USERNAME with your actual GitHub username
   vim .gitmodules
   ```

5. **Initialize submodules in main repo:**
   ```bash
   # Remove the directories (they'll be re-added as proper submodules)
   rm -rf grpc-mesh-node grpc-mesh-server
   
   # Initialize the submodules
   git submodule update --init --recursive
   
   # Commit the submodule configuration
   git add .gitmodules
   git commit -m "Add submodules: grpc-mesh-node and grpc-mesh-server"
   ```

6. **Push main repository:**
   ```bash
   git remote add origin https://github.com/YOUR_USERNAME/grpc-mesh.git
   git push -u origin main
   ```

### Option 2: Local Development (No Remote)

If you want to work locally without remote repositories for now:

```bash
# The current setup works fine for local development
# Just be aware that the .gitmodules URLs are placeholders

# To update submodules later when remotes are added:
git submodule sync
git submodule update --init --recursive
```

## Branch Naming Note

Currently:
- `grpc-mesh-node` uses `master` branch
- `grpc-mesh-server` uses `main` branch
- Main repository uses `main` branch

You may want to standardize on one naming convention:

```bash
# To rename grpc-mesh-node to 'main':
cd grpc-mesh-node
git branch -m master main
cd ..
```

## Working with Submodules

### Clone the project (after setup):
```bash
git clone --recursive https://github.com/YOUR_USERNAME/grpc-mesh.git
```

### Update submodules:
```bash
git submodule update --remote --merge
```

### Make changes in a submodule:
```bash
cd grpc-mesh-node
# Make changes
git add .
git commit -m "Your changes"
git push

cd ..
git add grpc-mesh-node
git commit -m "Update grpc-mesh-node submodule"
git push
```

## Alternative: Git Subtree (Optional)

If you prefer not to use submodules, you can use `git subtree` instead:

```bash
# Remove current directories
rm -rf grpc-mesh-node grpc-mesh-server

# Add as subtrees
git subtree add --prefix grpc-mesh-node \
  https://github.com/YOUR_USERNAME/grpc-mesh-node.git main --squash

git subtree add --prefix grpc-mesh-server \
  https://github.com/YOUR_USERNAME/grpc-mesh-server.git main --squash
```

Advantages of subtree:
- Simpler for contributors (no submodule commands needed)
- Works better with some CI/CD systems

Disadvantages:
- Slightly more complex to push changes back to submodules
- History can become cluttered

## Next Steps

1. Decide on remote hosting (GitHub, GitLab, etc.)
2. Create the three repositories
3. Follow the setup steps above
4. Update this file with the actual URLs
5. Consider adding CI/CD configurations

## Questions?

If you need help with the setup, refer to:
- [Git Submodules Documentation](https://git-scm.com/book/en/v2/Git-Tools-Submodules)
- [GitHub: Working with submodules](https://github.blog/2016-02-01-working-with-submodules/)