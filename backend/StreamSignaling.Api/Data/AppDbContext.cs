using Microsoft.EntityFrameworkCore;
using StreamSignaling.Api.Models;

namespace StreamSignaling.Api.Data
{
    /// <summary>
    /// Contexto de Entity Framework Core sobre SQL Server para usuarios y dispositivos vinculados.
    /// </summary>
    public class AppDbContext : DbContext
    {
        public AppDbContext(DbContextOptions<AppDbContext> options) : base(options)
        {
        }

        public DbSet<AppUser> Users => Set<AppUser>();

        public DbSet<LinkedDevice> LinkedDevices => Set<LinkedDevice>();

        protected override void OnModelCreating(ModelBuilder modelBuilder)
        {
            base.OnModelCreating(modelBuilder);

            modelBuilder.Entity<AppUser>(entity =>
            {
                entity.ToTable("AppUsers");
                entity.HasKey(e => e.Id);

                entity.Property(e => e.FirebaseUid)
                    .IsRequired()
                    .HasMaxLength(128);
                entity.HasIndex(e => e.FirebaseUid).IsUnique();

                entity.Property(e => e.Email).HasMaxLength(320);
                entity.Property(e => e.DisplayName).HasMaxLength(256);
                entity.Property(e => e.CreatedAtUtc).HasColumnType("datetime2");
            });

            modelBuilder.Entity<LinkedDevice>(entity =>
            {
                entity.ToTable("LinkedDevices");
                entity.HasKey(e => e.Id);

                entity.Property(e => e.FirebaseUid)
                    .IsRequired()
                    .HasMaxLength(128);
                entity.Property(e => e.DeviceHardwareId)
                    .IsRequired()
                    .HasMaxLength(256);
                entity.Property(e => e.DeviceName).HasMaxLength(256);
                entity.Property(e => e.FcmToken).HasMaxLength(4096);
                entity.Property(e => e.Role)
                    .IsRequired()
                    .HasMaxLength(32);
                entity.Property(e => e.LastSeenUtc).HasColumnType("datetime2");
                entity.Property(e => e.CreatedAtUtc).HasColumnType("datetime2");

                // Un dispositivo físico (hardware id) solo puede estar vinculado una vez por usuario.
                entity.HasIndex(e => new { e.FirebaseUid, e.DeviceHardwareId }).IsUnique();
                entity.HasIndex(e => e.DeviceHardwareId);

                entity.HasOne(e => e.User)
                    .WithMany(u => u.LinkedDevices)
                    .HasPrincipalKey(u => u.FirebaseUid)
                    .HasForeignKey(e => e.FirebaseUid)
                    .OnDelete(DeleteBehavior.Cascade);
            });
        }
    }
}
