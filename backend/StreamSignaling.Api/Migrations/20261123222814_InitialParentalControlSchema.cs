using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace StreamSignaling.Api.Migrations
{
    /// <inheritdoc />
    public partial class InitialParentalControlSchema : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "AppUsers",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    FirebaseUid = table.Column<string>(type: "nvarchar(128)", maxLength: 128, nullable: false),
                    Email = table.Column<string>(type: "nvarchar(320)", maxLength: 320, nullable: false),
                    DisplayName = table.Column<string>(type: "nvarchar(256)", maxLength: 256, nullable: false),
                    CreatedAtUtc = table.Column<DateTime>(type: "datetime2", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AppUsers", x => x.Id);
                    table.UniqueConstraint("AK_AppUsers_FirebaseUid", x => x.FirebaseUid);
                });

            migrationBuilder.CreateTable(
                name: "LinkedDevices",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    FirebaseUid = table.Column<string>(type: "nvarchar(128)", maxLength: 128, nullable: false),
                    DeviceHardwareId = table.Column<string>(type: "nvarchar(256)", maxLength: 256, nullable: false),
                    DeviceName = table.Column<string>(type: "nvarchar(256)", maxLength: 256, nullable: false),
                    FcmToken = table.Column<string>(type: "nvarchar(max)", maxLength: 4096, nullable: false),
                    Role = table.Column<string>(type: "nvarchar(32)", maxLength: 32, nullable: false),
                    IsOnline = table.Column<bool>(type: "bit", nullable: false),
                    LastSeenUtc = table.Column<DateTime>(type: "datetime2", nullable: false),
                    CreatedAtUtc = table.Column<DateTime>(type: "datetime2", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_LinkedDevices", x => x.Id);
                    table.ForeignKey(
                        name: "FK_LinkedDevices_AppUsers_FirebaseUid",
                        column: x => x.FirebaseUid,
                        principalTable: "AppUsers",
                        principalColumn: "FirebaseUid",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_AppUsers_FirebaseUid",
                table: "AppUsers",
                column: "FirebaseUid",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_LinkedDevices_DeviceHardwareId",
                table: "LinkedDevices",
                column: "DeviceHardwareId");

            migrationBuilder.CreateIndex(
                name: "IX_LinkedDevices_FirebaseUid_DeviceHardwareId",
                table: "LinkedDevices",
                columns: new[] { "FirebaseUid", "DeviceHardwareId" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "LinkedDevices");

            migrationBuilder.DropTable(
                name: "AppUsers");
        }
    }
}
